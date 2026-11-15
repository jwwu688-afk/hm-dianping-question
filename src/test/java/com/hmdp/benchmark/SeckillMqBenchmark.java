package com.hmdp.benchmark;

import com.hmdp.entity.VoucherOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消息队列（MQ 异步落库）与同步落库对比压测基准。
 *
 * 实验设计（1000 笔请求 / 秒杀券 10 / 初始库存 100，两组共用同一条 Lua 资格判断，
 * 差异只在「预扣成功之后怎么落库」——这正是简历里「接口响应不依赖 MySQL 写入」要量化的点）：
 *   C1 同步落库（无 MQ）：Lua 预扣成功后，请求线程自己完成 DB 扣库存 + 插订单，返回时已落库
 *   C2 MQ 异步（生产方案）：Lua 预扣成功后发送 RabbitMQ 立即返回，消费端异步落库
 *
 * 消费端复刻生产 VoucherOrderServiceImpl#createVoucherOrder 逻辑（无条件扣减 + 插单），
 * 消费者并发度 1，与生产 @RabbitListener 默认一致。
 *
 * 指标：接口侧延迟 p50/p95/p99（C1 含落库，C2 只到消息发送完成）/ 墙钟 / 落库完成总时长 /
 *       C2 落库节奏采样曲线（体现「削峰」：瞬时 1000 笔请求 vs 消费端匀速消化）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeckillMqBenchmark {

    static final long VOUCHER_ID = 10L;
    static final int THREADS = 1000;
    static final int INIT_STOCK = 100;
    static final int DB_POOL_SIZE = 50;

    static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    static final String DB_USER = "root";
    static final String DB_PASS = "123456";

    // RabbitMQ 凭据与生产 docker-compose 一致；交换机/队列名与生产 OrderRabbitConfig 一致
    static final String MQ_HOST = "127.0.0.1";
    static final int MQ_PORT = 5672;
    static final String MQ_USER = "root";
    static final String MQ_PASS = "123456";
    static final String EXCHANGE = "hmdp.order.fanout.exchange";
    static final String QUEUE = "hmdp.order.queue";

    static final Path REPORT = Path.of("benchmark_out", "mq_report.md");

    static BlockingQueue<Connection> dbPool;
    static StringRedisTemplate redis;
    static DefaultRedisScript<Long> script;
    static RabbitTemplate rabbit;

    /** 建立连接池与 Redis/RabbitMQ 客户端。 */
    @BeforeAll
    static void init() throws Exception {
        dbPool = new LinkedBlockingQueue<>();
        for (int i = 0; i < DB_POOL_SIZE; i++) {
            dbPool.offer(DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS));
        }
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        conf.setPassword(RedisPassword.of("123456"));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(conf);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
        // RabbitTemplate 与生产配置对齐：Jackson JSON 序列化，信任包 com.hmdp.entity
        CachingConnectionFactory mqFactory = new CachingConnectionFactory(MQ_HOST, MQ_PORT);
        mqFactory.setUsername(MQ_USER);
        mqFactory.setPassword(MQ_PASS);
        rabbit = new RabbitTemplate(mqFactory);
        rabbit.setMessageConverter(new Jackson2JsonMessageConverter("com.hmdp.entity"));
        Files.createDirectories(REPORT.getParent());
    }

    /** 声明交换机/队列/绑定（幂等，与生产 OrderRabbitConfig 同名同型），并清空残留消息。 */
    static void declareMq() {
        // 注意用全限定名：本文件已 import java.sql.Connection，直接写 Connection 会类型混淆
        try (com.rabbitmq.client.Connection c = mqAdmin()) {
            com.rabbitmq.client.Channel ch = c.createChannel();
            try {
                ch.exchangeDeclare(EXCHANGE, "fanout", true);
                ch.queueDeclare(QUEUE, true, false, false, null);
                ch.queueBind(QUEUE, EXCHANGE, "");
                ch.queuePurge(QUEUE);            // 清掉上次运行残留的消息，保证两组起点干净
            } finally {
                ch.close();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static com.rabbitmq.client.Connection mqAdmin() throws Exception {
        com.rabbitmq.client.ConnectionFactory f = new com.rabbitmq.client.ConnectionFactory();
        f.setHost(MQ_HOST);
        f.setPort(MQ_PORT);
        f.setUsername(MQ_USER);
        f.setPassword(MQ_PASS);
        return f.newConnection();
    }

    /** 统一重置现场：MySQL 库存/订单清零、Redis 预热库存与一人一单集合。 */
    static void resetEnv() throws Exception {
        try (Connection c = dbPool.take()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE tb_seckill_voucher SET stock = ? WHERE voucher_id = ?")) {
                ps.setInt(1, INIT_STOCK);
                ps.setLong(2, VOUCHER_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM tb_voucher_order WHERE voucher_id = ?")) {
                ps.setLong(1, VOUCHER_ID);
                ps.executeUpdate();
            }
        }
        redis.delete("seckill:stock:" + VOUCHER_ID);
        redis.delete("seckill:order:" + VOUCHER_ID);
        redis.opsForValue().set("seckill:stock:" + VOUCHER_ID, String.valueOf(INIT_STOCK));
    }

    /** 生产同款消费者落库逻辑：无条件扣 MySQL 库存 + 插订单（资格已被 Redis 层把关）。 */
    static void createVoucherOrder(VoucherOrder order) {
        Connection c = null;
        try {
            c = dbPool.take();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ?")) {
                ps.setLong(1, order.getVoucherId());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status) VALUES (?, ?, ?, 1, 1)")) {
                ps.setLong(1, order.getId());
                ps.setLong(2, order.getUserId());
                ps.setLong(3, order.getVoucherId());
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (c != null) dbPool.offer(c);
        }
    }

    static synchronized void write(String line) {
        try {
            Files.writeString(REPORT, line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println(line);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String pct(long[] latencies) {
        Arrays.sort(latencies);
        int n = latencies.length;
        return String.format("p50=%.1fms p95=%.1fms p99=%.1fms",
                latencies[n / 2] / 1e6, latencies[(int) (n * 0.95)] / 1e6, latencies[(int) (n * 0.99)] / 1e6);
    }

    interface ThrowingIntAction {
        boolean apply(int idx) throws Exception;
    }

    /** 栅栏对齐并发执行。 */
    static RunResult runConcurrent(ThrowingIntAction action) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        long[] latencies = new long[THREADS];
        AtomicInteger success = new AtomicInteger();
        long start = System.nanoTime();
        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    barrier.await();
                    long t0 = System.nanoTime();
                    boolean ok = action.apply(idx);
                    latencies[idx] = System.nanoTime() - t0;
                    if (ok) success.incrementAndGet();
                } catch (Exception ignored) {
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.MINUTES);
        return new RunResult(success.get(), (System.nanoTime() - start) / 1_000_000, latencies);
    }

    record RunResult(int success, long wallMs, long[] latencies) {}

    static int orderCount(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?")) {
            ps.setLong(1, VOUCHER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    static int dbStock(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?")) {
            ps.setLong(1, VOUCHER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @BeforeAll
    static void reportHeader() {
        write("");
        write("## MQ 异步 vs 同步落库消融（1000 笔请求 / 初始库存 100 / 消费者并发 1）");
        write("");
        write("| 方案 | 成功受理 | 接口侧延迟（单请求） | 墙钟（全部受理） | 落库完成总时长 |");
        write("|---|---|---|---|---|");
    }

    /** C1 同步落库（无 MQ）：Lua 预扣成功后，请求线程自己完成 DB 扣减与插单，返回时已全部落库。 */
    @Test
    @Order(1)
    void C1_syncDb() throws Exception {
        resetEnv();
        declareMq();
        RunResult r = runConcurrent(idx -> {
            // 第一步：与生产一致的 Lua 资格判断 + 预扣
            Long res = redis.execute(script,
                    Arrays.asList("seckill:stock:" + VOUCHER_ID, "seckill:order:" + VOUCHER_ID),
                    String.valueOf(VOUCHER_ID), String.valueOf(idx + 1),
                    String.valueOf(9_200_000_000_000_000L + idx));
            if (res == null || res != 0L) return false;   // 0 = 拿到资格
            // 第二步：同步方案里，请求线程自己完成 DB 落库（扣库存 + 插订单），返回前全部做完
            createVoucherOrder(new VoucherOrder() {{
                setId(9_200_000_000_000_000L + idx);
                setUserId((long) (idx + 1));
                setVoucherId(VOUCHER_ID);
            }});
            return true;
        });
        try (Connection c = dbPool.take()) {
            write(String.format("| C1 同步落库(无MQ) | %d | %s | %dms | %dms（受理即全部落库） |",
                    r.success(), pct(r.latencies()), r.wallMs(), r.wallMs()));
        }
    }

    /** C2 MQ 异步（生产方案）：Lua 预扣成功后发送 RabbitMQ 立即返回，单消费者异步落库。 */
    @Test
    @Order(2)
    void C2_mqAsync() throws Exception {
        resetEnv();
        declareMq();
        // 启动 1 个消费者（与生产 @RabbitListener 默认并发一致），复刻生产 createVoucherOrder 逻辑
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        CachingConnectionFactory mqFactory = new CachingConnectionFactory(MQ_HOST, MQ_PORT);
        mqFactory.setUsername(MQ_USER);
        mqFactory.setPassword(MQ_PASS);
        container.setConnectionFactory(mqFactory);
        container.setQueueNames(QUEUE);
        AtomicInteger consumed = new AtomicInteger();
        // 消费端直接按 JSON body 解析（发送端是 Jackson JSON，消息体即 VoucherOrder 的 JSON）
        container.setMessageListener(message -> {
            try {
                VoucherOrder order = cn.hutool.json.JSONUtil.toBean(
                        new String(message.getBody(), StandardCharsets.UTF_8), VoucherOrder.class);
                createVoucherOrder(order);
                consumed.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        container.start();

        RunResult r = runConcurrent(idx -> {
            // 与生产完全一致：Lua 预扣成功 → 组装订单 → 发送 MQ → 立即返回（不等落库）
            Long res = redis.execute(script,
                    Arrays.asList("seckill:stock:" + VOUCHER_ID, "seckill:order:" + VOUCHER_ID),
                    String.valueOf(VOUCHER_ID), String.valueOf(idx + 1),
                    String.valueOf(9_300_000_000_000_000L + idx));
            if (res == null || res != 0L) return false;
            VoucherOrder order = new VoucherOrder();
            order.setId(9_300_000_000_000_000L + idx);
            order.setUserId((long) (idx + 1));
            order.setVoucherId(VOUCHER_ID);
            rabbit.convertAndSend(EXCHANGE, "", order);
            return true;
        });

        // 轮询等待消费端全部落库完成，同时记录落库节奏（削峰曲线）
        List<String> curve = new ArrayList<>();
        long settleStart = System.nanoTime();
        int done = 0;
        while (System.nanoTime() - settleStart < TimeUnit.SECONDS.toNanos(120)) {
            Thread.sleep(200);
            Connection c = dbPool.take();
            try {
                done = orderCount(c);
            } finally {
                dbPool.offer(c);                 // 归还连接（try-with-resources 会 close 连接，不能用）
            }
            long elapsedMs = (System.nanoTime() - settleStart) / 1_000_000;
            curve.add(elapsedMs + "ms:" + done + "笔");
            if (done >= r.success()) break;
        }
        long settleMs = (System.nanoTime() - settleStart) / 1_000_000;
        container.stop();

        write(String.format("| C2 MQ异步(生产) | %d | %s | %dms（受理） | %dms（全部落库完成） |",
                r.success(), pct(r.latencies()), r.wallMs(), settleMs));
        write("|  └ 落库节奏: " + String.join(" → ", curve) + " |");
        write("|  └ 消费端实际消费 " + consumed.get() + " 笔；接口侧延迟不含落库，用户感知为受理即返回 |");
    }
}
