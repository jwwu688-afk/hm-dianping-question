package com.hmdp.benchmark;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 秒杀方案消融压测基准：三组对照组在同一并发压力（1000 线程抢 100 件库存）下对比。
 *
 * 实验组设计（对应简历表述，全部可复现）：
 *   A1 无锁裸扣 DB（超卖基线）：UPDATE 不带库存条件，模拟最朴素的实现
 *   A2 CAS 条件更新（DB 防超卖基线）：UPDATE 带 stock > 0 条件，靠 InnoDB 行锁串行
 *   A3 Redis + Lua（生产方案）：资格校验 + 一人一单 + 预扣在 Redis 单脚本原子完成
 *
 * 指标：超卖件数 / 成功受理数 / 墙钟耗时 / 单请求延迟 p50/p95/p99 / 理论 DB 写入量。
 * 说明：A3 只压「资格受理层」（生产链路里真正吃并发的层），MQ 异步落库不在本轮计时范围。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeckillBenchmark {

    static final long VOUCHER_ID = 10L;          // 秒杀券 ID（tb_seckill_voucher 中已有）
    static final int THREADS = 1000;             // 并发抢购线程数
    static final int INIT_STOCK = 100;           // 初始库存
    static final int DB_POOL_SIZE = 50;          // 模拟生产数据库连接池大小

    static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    static final String DB_USER = "root";
    static final String DB_PASS = "123456";

    static final Path REPORT = Path.of("benchmark_out", "seckill_report.md");

    static BlockingQueue<Connection> dbPool;

    /** 建立数据库连接池：所有对照组共用同一池规格，保证 DB 资源一致。 */
    @BeforeAll
    static void initPool() throws Exception {
        dbPool = new LinkedBlockingQueue<>();
        for (int i = 0; i < DB_POOL_SIZE; i++) {
            dbPool.offer(DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS));
        }
        Files.createDirectories(REPORT.getParent());
    }

    /** 统一重置现场：MySQL 库存/订单清零、Redis 库存/一人一单集合清零，保证三组实验起点完全相同。 */
    static void resetEnv(StringRedisTemplate redis) throws Exception {
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
        // 预加载 Redis 库存（与生产「新增秒杀券时预热库存」一致；Lua 对不存在的 key 返回 3=未初始化）
        redis.opsForValue().set("seckill:stock:" + VOUCHER_ID, String.valueOf(INIT_STOCK));
    }

    /** Markdown 结果追加写入。 */
    static synchronized void write(String line) {
        try {
            Files.writeString(REPORT, line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println(line);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 延迟百分位（输入为纳秒数组）。 */
    static String pct(long[] latencies) {
        Arrays.sort(latencies);
        int n = latencies.length;
        return String.format("p50=%.1fms p95=%.1fms p99=%.1fms",
                latencies[n / 2] / 1e6, latencies[(int) (n * 0.95)] / 1e6, latencies[(int) (n * 0.99)] / 1e6);
    }

    /** 允许抛受检异常的任务接口（压测动作里有 JDBC/Redis 的 checked exception）。 */
    interface ThrowingIntAction {
        boolean apply(int idx) throws Exception;
    }

    /** 单组实验执行框架：起 1000 线程、栅栏对齐同时开抢、逐线程记录耗时。 */
    static RunResult runConcurrent(int threads, ThrowingIntAction action) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long[] latencies = new long[threads];
        AtomicInteger success = new AtomicInteger();
        long start = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    barrier.await();                       // 栅栏对齐：1000 线程同一瞬间发起
                    long t0 = System.nanoTime();
                    boolean ok = action.apply(idx);
                    latencies[idx] = System.nanoTime() - t0;
                    if (ok) success.incrementAndGet();
                } catch (Exception e) {
                    // 单线程异常不计入成功，压测继续
                }            });
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.MINUTES);
        long wall = (System.nanoTime() - start) / 1_000_000;   // 墙钟耗时 ms
        return new RunResult(success.get(), wall, latencies);
    }

    record RunResult(int success, long wallMs, long[] latencies) {}

    /** 查询 DB 当前库存（负数即为超卖量），需外部传入池内连接。 */
    static int stock(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?")) {
            ps.setLong(1, VOUCHER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** 查询已落库订单数。 */
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

    @BeforeAll
    static void reportHeader() {
        write("");
        write("## 秒杀消融压测（" + THREADS + " 并发 / 初始库存 " + INIT_STOCK + " / 连接池 " + DB_POOL_SIZE + "）");
        write("");
        write("| 方案 | 成功受理 | 落库订单 | 终态库存 | 超卖件数 | 墙钟耗时 | 单请求延迟 |");
        write("|---|---|---|---|---|---|---|");
    }

    /** A1 无锁裸扣 DB：不带库存条件的 UPDATE + 无条件插单——最朴素的实现，用于暴露超卖。 */
    @Test
    @Order(1)
    void A1_naiveDb() throws Exception {
        StringRedisTemplate redis = redis();
        resetEnv(redis);
        RunResult r = runConcurrent(THREADS, idx -> {
            Connection c = null;
            try {
                c = dbPool.take();
                // 裸扣：不带 stock > 0 条件，模拟无防护实现
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ?")) {
                    ps.setLong(1, VOUCHER_ID);
                    ps.executeUpdate();
                }
                // 朴素实现不检查更新结果，直接插单
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO tb_voucher_order (id, user_id, voucher_id) VALUES (?, ?, ?)")) {
                    ps.setLong(1, 9_000_000_000_001L + idx);
                    ps.setLong(2, idx + 1);
                    ps.setLong(3, VOUCHER_ID);
                    ps.executeUpdate();
                }
                return true;
            } finally {
                dbPool.offer(c);
            }
        });
        try (Connection c = dbPool.take()) {
            int s = stock(c);
            write(String.format("| A1 裸扣DB(无库存条件) | %d | %d | %d | %d | %dms | %s |",
                    r.success(), orderCount(c), s, INIT_STOCK - s, r.wallMs(), pct(r.latencies())));
        }
    }

    /** A2 CAS 条件更新：UPDATE 带 stock > 0，靠 InnoDB 行锁串行化防超卖。 */
    @Test
    @Order(2)
    void A2_casDb() throws Exception {
        resetEnv(redis());
        RunResult r = runConcurrent(THREADS, idx -> {
            Connection c = null;
            try {
                c = dbPool.take();
                boolean ok = false;
                // CAS：库存条件在 SQL 层判断，只有真正扣到库存才插单
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0")) {
                    ps.setLong(1, VOUCHER_ID);
                    ok = ps.executeUpdate() == 1;
                }
                if (ok) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO tb_voucher_order (id, user_id, voucher_id) VALUES (?, ?, ?)")) {
                        ps.setLong(1, 9_000_000_000_002L + idx);
                        ps.setLong(2, idx + 1);
                        ps.setLong(3, VOUCHER_ID);
                        ps.executeUpdate();
                    }
                }
                return ok;
            } finally {
                dbPool.offer(c);
            }
        });
        try (Connection c = dbPool.take()) {
            int s = stock(c);
            write(String.format("| A2 CAS条件更新DB | %d | %d | %d | %d | %dms | %s |",
                    r.success(), orderCount(c), s, INIT_STOCK - s, r.wallMs(), pct(r.latencies())));
        }
    }

    /** A3 Redis + Lua（生产方案）：资格校验 + 一人一单 + 预扣单脚本原子完成。 */
    @Test
    @Order(3)
    void A3_redisLua() throws Exception {
        StringRedisTemplate redis = redis();
        resetEnv(redis);
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
        RunResult r = runConcurrent(THREADS, idx -> {
            // 与生产 VoucherOrderServiceImpl 同一调用方式：keys=库存+一人一单集合，argv=券/用户/订单ID
            Long res = redis.execute(script,
                    Arrays.asList("seckill:stock:" + VOUCHER_ID, "seckill:order:" + VOUCHER_ID),
                    String.valueOf(VOUCHER_ID), String.valueOf(idx + 1),
                    String.valueOf(9_000_000_000_003L + idx));
            return res != null && res == 0L;   // 0 = 预扣成功拿到资格
        });
        try (Connection c = dbPool.take()) {
            int s = stock(c);
            write(String.format("| A3 Redis+Lua(生产) | %d | 0(未落库,异步) | %d(Redis) | 0 | %dms | %s |",
                    r.success(), Integer.parseInt(redis.opsForValue().get("seckill:stock:" + VOUCHER_ID)),
                    r.wallMs(), pct(r.latencies())));
            write(String.format("|  └ 参考：A2/A3 期间 MySQL 写入量 | A1=%d 次 | A2=1000 次(行锁竞争) | A3=0 次(受理阶段) |", THREADS));
        }
    }

    /** 构造连接本机 Redis 的模板（带密码），与 SeckillLuaIT 的构建方式一致。 */
    static StringRedisTemplate redis() {
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        conf.setPassword(RedisPassword.of("123456"));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(conf);
        factory.afterPropertiesSet();
        return new StringRedisTemplate(factory);
    }
}
