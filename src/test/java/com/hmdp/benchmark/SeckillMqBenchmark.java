// 包声明：MQ 异步 vs 同步落库消融压测所在包
package com.hmdp.benchmark;

// VoucherOrder：订单实体（MQ 消息体）
import com.hmdp.entity.VoucherOrder;
// BeforeAll：静态初始化注解
import org.junit.jupiter.api.BeforeAll;
// MethodOrderer：测试排序器
import org.junit.jupiter.api.MethodOrderer;
// Order：顺序注解
import org.junit.jupiter.api.Order;
// Test：测试方法注解
import org.junit.jupiter.api.Test;
// TestMethodOrder：声明按注解值排序
import org.junit.jupiter.api.TestMethodOrder;
// CachingConnectionFactory：带连接缓存的 RabbitMQ 连接工厂
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
// RabbitTemplate：消息发送模板
import org.springframework.amqp.rabbit.core.RabbitTemplate;
// SimpleMessageListenerContainer：消息监听容器（消费者载体）
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
// Jackson2JsonMessageConverter：JSON 消息转换器（与生产一致）
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
// ClassPathResource：加载 seckill.lua
import org.springframework.core.io.ClassPathResource;
// RedisPassword：Redis 密码值对象
import org.springframework.data.redis.connection.RedisPassword;
// RedisStandaloneConfiguration：单机 Redis 配置
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
// LettuceConnectionFactory：Lettuce 连接工厂
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
// StringRedisTemplate：Redis 字符串模板
import org.springframework.data.redis.core.StringRedisTemplate;
// DefaultRedisScript：Lua 脚本执行器
import org.springframework.data.redis.core.script.DefaultRedisScript;

// StandardCharsets：UTF-8 字符集
import java.nio.charset.StandardCharsets;
// Files：文件读写工具
import java.nio.file.Files;
// Path：路径对象
import java.nio.file.Path;
// StandardOpenOption：打开选项（创建/追加）
import java.nio.file.StandardOpenOption;
// Connection：数据库连接
import java.sql.Connection;
// DriverManager：JDBC 建连管理器
import java.sql.DriverManager;
// PreparedStatement：预编译语句
import java.sql.PreparedStatement;
// ResultSet：结果集游标
import java.sql.ResultSet;
// ArrayList：列表实现
import java.util.ArrayList;
// Arrays：数组工具
import java.util.Arrays;
// List：列表接口
import java.util.List;
// BlockingQueue：阻塞队列（模拟连接池）
import java.util.concurrent.BlockingQueue;
// CyclicBarrier：循环栅栏（并发对齐）
import java.util.concurrent.CyclicBarrier;
// ExecutorService：线程池服务
import java.util.concurrent.ExecutorService;
// Executors：线程池工厂
import java.util.concurrent.Executors;
// LinkedBlockingQueue：链表阻塞队列
import java.util.concurrent.LinkedBlockingQueue;
// TimeUnit：时间单位
import java.util.concurrent.TimeUnit;
// AtomicInteger：原子计数器
import java.util.concurrent.atomic.AtomicInteger;

// ===== 类级实验设计说明：以下原 Javadoc 保留 =====
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
// 按 @Order 顺序执行：C1 → C2
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// MQ 异步 vs 同步落库消融压测类（包级私有）
class SeckillMqBenchmark {

// ===== 压测参数区 =====
    static final long VOUCHER_ID = 10L;
// 并发模型：1000 线程栅栏对齐同时发起
    static final int THREADS = 1000;
// 初始库存 100 件
    static final int INIT_STOCK = 100;
// DB 连接池规格（两组一致）
    static final int DB_POOL_SIZE = 50;

// 本机 MySQL 连接串
    static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
// 数据库账号
    static final String DB_USER = "root";
// 数据库密码
    static final String DB_PASS = "123456";

    // RabbitMQ 凭据与生产 docker-compose 一致；交换机/队列名与生产 OrderRabbitConfig 一致
// MQ 主机地址
    static final String MQ_HOST = "127.0.0.1";
// MQ 端口
    static final int MQ_PORT = 5672;
// MQ 用户名
    static final String MQ_USER = "root";
// MQ 密码
    static final String MQ_PASS = "123456";
// 订单交换机（fanout，与生产 OrderRabbitConfig 同名）
    static final String EXCHANGE = "hmdp.order.fanout.exchange";
// 订单队列（与生产同名）
    static final String QUEUE = "hmdp.order.queue";

// 报告输出路径
    static final Path REPORT = Path.of("benchmark_out", "mq_report.md");

// 自建 DB 连接池
    static BlockingQueue<Connection> dbPool;
// Redis 操作模板
    static StringRedisTemplate redis;
// Lua 脚本执行器
    static DefaultRedisScript<Long> script;
// Rabbit 发送模板
    static RabbitTemplate rabbit;

// ===== 初始化区 =====
    /** 建立连接池与 Redis/RabbitMQ 客户端。 */
// 生命周期：类加载后执行一次
    @BeforeAll
// init 方法体
    static void init() throws Exception {
// 建 50 连接自建池
        dbPool = new LinkedBlockingQueue<>();
// 循环建连
        for (int i = 0; i < DB_POOL_SIZE; i++) {
// 连接入池
            dbPool.offer(DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS));
// 循环结束
        }
// 单机 Redis 连接配置
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration("127.0.0.1", 6379);
// 设置访问密码
        conf.setPassword(RedisPassword.of("123456"));
// 创建 Lettuce 工厂
        LettuceConnectionFactory factory = new LettuceConnectionFactory(conf);
// 初始化工厂
        factory.afterPropertiesSet();
// 构建 Redis 模板
        redis = new StringRedisTemplate(factory);
// 创建 Lua 脚本执行器
        script = new DefaultRedisScript<>();
// 指定脚本位置
        script.setLocation(new ClassPathResource("seckill.lua"));
// 指定返回类型 Long
        script.setResultType(Long.class);
        // RabbitTemplate 与生产配置对齐：Jackson JSON 序列化，信任包 com.hmdp.entity
// 创建 MQ 缓存连接工厂
        CachingConnectionFactory mqFactory = new CachingConnectionFactory(MQ_HOST, MQ_PORT);
// 设置 MQ 用户名
        mqFactory.setUsername(MQ_USER);
// 设置 MQ 密码
        mqFactory.setPassword(MQ_PASS);
// 构建发送模板
        rabbit = new RabbitTemplate(mqFactory);
// 设置 JSON 消息转换器（信任 com.hmdp.entity 包）
        rabbit.setMessageConverter(new Jackson2JsonMessageConverter("com.hmdp.entity"));
// 确保报告目录存在
        Files.createDirectories(REPORT.getParent());
// init 方法结束
    }

// ===== MQ 拓扑声明 =====
    /** 声明交换机/队列/绑定（幂等，与生产 OrderRabbitConfig 同名同型），并清空残留消息。 */
// 声明交换机/队列/绑定并清空残留消息
    static void declareMq() {
        // 注意用全限定名：本文件已 import java.sql.Connection，直接写 Connection 会类型混淆
// 建立管理连接（amqp 原生客户端，避免与 java.sql.Connection 混淆）
        try (com.rabbitmq.client.Connection c = mqAdmin()) {
// 创建信道
            com.rabbitmq.client.Channel ch = c.createChannel();
// try-finally 保证信道关闭
            try {
// 声明 fanout 交换机（持久化）
                ch.exchangeDeclare(EXCHANGE, "fanout", true);
// 声明队列（持久化、非独占）
                ch.queueDeclare(QUEUE, true, false, false, null);
// 绑定队列到交换机
                ch.queueBind(QUEUE, EXCHANGE, "");
// 清掉上次运行残留的消息
                ch.queuePurge(QUEUE);            // 清掉上次运行残留的消息，保证两组起点干净
// finally：关闭信道
            } finally {
// 关闭信道
                ch.close();
// finally 块结束
            }
// 管理操作异常捕获
        } catch (Exception e) {
// 包装为运行时异常上抛
            throw new RuntimeException(e);
// catch 块结束
        }
// declareMq 方法结束
    }

// 原生 amqp 连接工厂构建工具
    private static com.rabbitmq.client.Connection mqAdmin() throws Exception {
// 创建连接工厂实例
        com.rabbitmq.client.ConnectionFactory f = new com.rabbitmq.client.ConnectionFactory();
// 设置主机
        f.setHost(MQ_HOST);
// 设置端口
        f.setPort(MQ_PORT);
// 设置用户名
        f.setUsername(MQ_USER);
// 设置密码
        f.setPassword(MQ_PASS);
// 建立并返回连接
        return f.newConnection();
// mqAdmin 方法结束
    }

// ===== 现场重置 =====
    /** 统一重置现场：MySQL 库存/订单清零、Redis 预热库存与一人一单集合。 */
// 统一重置现场方法体
    static void resetEnv() throws Exception {
// 借池内连接
        try (Connection c = dbPool.take()) {
// 创建库存重置语句
            try (PreparedStatement ps = c.prepareStatement(
// UPDATE 文本：恢复库存初值
                    "UPDATE tb_seckill_voucher SET stock = ? WHERE voucher_id = ?")) {
// 绑定库存初值参数
                ps.setInt(1, INIT_STOCK);
// 绑定券 ID 参数
                ps.setLong(2, VOUCHER_ID);
// 执行库存重置
                ps.executeUpdate();
// 库存重置语句结束
            }
// 创建订单清空语句
            try (PreparedStatement ps = c.prepareStatement(
// DELETE 文本：清空该券订单
                    "DELETE FROM tb_voucher_order WHERE voucher_id = ?")) {
// 绑定券 ID 参数
                ps.setLong(1, VOUCHER_ID);
// 执行订单清空
                ps.executeUpdate();
// 订单清空语句结束
            }
// MySQL 现场重置完毕
        }
// 删除 Redis 库存 key
        redis.delete("seckill:stock:" + VOUCHER_ID);
// 删除一人一单集合
        redis.delete("seckill:order:" + VOUCHER_ID);
// 预热库存到初始值
        redis.opsForValue().set("seckill:stock:" + VOUCHER_ID, String.valueOf(INIT_STOCK));
// resetEnv 方法结束
    }

// ===== 消费端落库逻辑 =====
    /** 生产同款消费者落库逻辑：无条件扣 MySQL 库存 + 插订单（资格已被 Redis 层把关）。 */
// 生产同款消费者落库逻辑
    static void createVoucherOrder(VoucherOrder order) {
// 连接引用（finally 归还）
        Connection c = null;
// try-finally 保证归还
        try {
// 从池借连接
            c = dbPool.take();
// 创建无条件扣减语句
            try (PreparedStatement ps = c.prepareStatement(
// UPDATE 文本：无库存条件（资格已由 Redis 层把关）
                    "UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ?")) {
// 绑定券 ID
                ps.setLong(1, order.getVoucherId());
// 执行扣减
                ps.executeUpdate();
// 扣减语句结束
            }
// 创建插单语句
            try (PreparedStatement ps = c.prepareStatement(
// INSERT 文本：支付类型/状态写死初始值
                    "INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status) VALUES (?, ?, ?, 1, 1)")) {
// 绑定订单 ID
                ps.setLong(1, order.getId());
// 绑定用户 ID
                ps.setLong(2, order.getUserId());
// 绑定券 ID
                ps.setLong(3, order.getVoucherId());
// 执行插单
                ps.executeUpdate();
// 插单语句结束
            }
// 落库异常捕获
        } catch (Exception e) {
// 包装为运行时异常上抛
            throw new RuntimeException(e);
// finally：归还连接
        } finally {
// 归还连接到池
            if (c != null) dbPool.offer(c);
// finally 块结束
        }
// createVoucherOrder 方法结束
    }

// 报告写入工具（synchronized 防交叉）
    static synchronized void write(String line) {
// try-catch 包裹
        try {
// 追加写一行报告
            Files.writeString(REPORT, line + System.lineSeparator(),
// 参数续行：UTF-8 + 创建/追加
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
// 同步打印控制台
            System.out.println(line);
// 写文件异常捕获
        } catch (Exception e) {
// 包装上抛
            throw new RuntimeException(e);
// catch 块结束
        }
// write 方法结束
    }

// 延迟百分位计算（p50/p95/p99）
    static String pct(long[] latencies) {
// 样本升序排序
        Arrays.sort(latencies);
// 样本总数
        int n = latencies.length;
// 格式化输出三分位
        return String.format("p50=%.1fms p95=%.1fms p99=%.1fms",
// 下标取值与纳秒转毫秒续行
                latencies[n / 2] / 1e6, latencies[(int) (n * 0.95)] / 1e6, latencies[(int) (n * 0.99)] / 1e6);
// pct 方法结束
    }

// 函数式接口：压测动作统一签名
    interface ThrowingIntAction {
// apply：入参线程编号，返回是否受理
        boolean apply(int idx) throws Exception;
// 接口结束
    }

// ===== 并发执行框架 =====
    /** 栅栏对齐并发执行。 */
// 栅栏对齐并发执行框架
    static RunResult runConcurrent(ThrowingIntAction action) throws Exception {
// 栅栏：1000 线程到齐后同时放行
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
// 固定大小线程池
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
// 逐线程延迟样本数组
        long[] latencies = new long[THREADS];
// 成功受理计数器（原子）
        AtomicInteger success = new AtomicInteger();
// 墙钟起点
        long start = System.nanoTime();
// 提交任务循环
        for (int i = 0; i < THREADS; i++) {
// 线程编号捕获
            final int idx = i;
// 提交单个任务
            pool.submit(() -> {
// 任务体：对齐→计时→执行
                try {
// 栅栏对齐：同一瞬间发起
                    barrier.await();
// 请求开始时刻
                    long t0 = System.nanoTime();
// 执行被测动作
                    boolean ok = action.apply(idx);
// 记录逐线程耗时
                    latencies[idx] = System.nanoTime() - t0;
// 受理成功则累加
                    if (ok) success.incrementAndGet();
// 异常捕获：单线程失败忽略
                } catch (Exception ignored) {
// catch 块结束
                }
// lambda 结束
            });
// 提交循环结束
        }
// 不再接收新任务
        pool.shutdown();
// 等待全部线程终止（上限 5 分钟）
        pool.awaitTermination(5, TimeUnit.MINUTES);
// 打包返回受理数/墙钟/延迟样本
        return new RunResult(success.get(), (System.nanoTime() - start) / 1_000_000, latencies);
// runConcurrent 方法结束
    }

// 结果记录：成功数 + 墙钟 + 逐线程延迟
    record RunResult(int success, long wallMs, long[] latencies) {}

// 查询已落库订单数
    static int orderCount(Connection c) throws Exception {
// 创建统计语句
        try (PreparedStatement ps = c.prepareStatement(
// COUNT 文本：按券统计订单
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?")) {
// 绑定券 ID
            ps.setLong(1, VOUCHER_ID);
// 执行查询
            try (ResultSet rs = ps.executeQuery()) {
// 游标移到首行
                rs.next();
// 返回订单总数
                return rs.getInt(1);
// ResultSet 资源块结束
            }
// PreparedStatement 资源块结束
        }
// orderCount 方法结束
    }

// 查询 DB 当前库存
    static int dbStock(Connection c) throws Exception {
// 创建库存查询语句
        try (PreparedStatement ps = c.prepareStatement(
// SELECT 文本：按券查库存
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?")) {
// 绑定券 ID
            ps.setLong(1, VOUCHER_ID);
// 执行查询
            try (ResultSet rs = ps.executeQuery()) {
// 游标移到首行
                rs.next();
// 返回库存值
                return rs.getInt(1);
// ResultSet 资源块结束
            }
// PreparedStatement 资源块结束
        }
// dbStock 方法结束
    }

    @BeforeAll
// 生命周期：写报告表头
    static void reportHeader() {
// 空行分隔
        write("");
// 压测概览标题
        write("## MQ 异步 vs 同步落库消融（1000 笔请求 / 初始库存 100 / 消费者并发 1）");
// 空行
        write("");
// 结果表列头
        write("| 方案 | 成功受理 | 接口侧延迟（单请求） | 墙钟（全部受理） | 落库完成总时长 |");
// Markdown 分隔行
        write("|---|---|---|---|---|");
// reportHeader 方法结束
    }

// ===== 对照组 C1 =====
    /** C1 同步落库（无 MQ）：Lua 预扣成功后，请求线程自己完成 DB 扣减与插单，返回时已全部落库。 */
// 对照组 C1：同步落库入口
    @Test
// 执行顺序 1
    @Order(1)
// C1 主体：重置→声明MQ→栅栏→Lua+同步落库→统计
    void C1_syncDb() throws Exception {
// 重置现场
        resetEnv();
// 声明 MQ 拓扑（两组实验条件一致）
        declareMq();
// 起压：1000 线程并发执行 C1 动作
        RunResult r = runConcurrent(idx -> {
            // 第一步：与生产一致的 Lua 资格判断 + 预扣
// 第一步：执行 Lua 资格判断+预扣
            Long res = redis.execute(script,
// keys 续行：库存 key + 一人一单集合
                    Arrays.asList("seckill:stock:" + VOUCHER_ID, "seckill:order:" + VOUCHER_ID),
// argv 续行：券 ID / 用户 ID
                    String.valueOf(VOUCHER_ID), String.valueOf(idx + 1),
// argv 续行：订单 ID（C1 段固定前缀）
                    String.valueOf(9_200_000_000_000_000L + idx));
// 未拿到资格则受理失败
            if (res == null || res != 0L) return false;   // 0 = 拿到资格
            // 第二步：同步方案里，请求线程自己完成 DB 落库（扣库存 + 插订单），返回前全部做完
// 第二步：同步落库，请求线程自己完成 DB 写入
            createVoucherOrder(new VoucherOrder() {{
// 双括号匿名子类并设置订单 ID
                setId(9_200_000_000_000_000L + idx);
// 设置用户 ID
                setUserId((long) (idx + 1));
// 设置券 ID
                setVoucherId(VOUCHER_ID);
// 匿名类初始化块结束
            }});
// 受理成功返回
            return true;
// C1 压测动作 lambda 结束
        });
// 借连接做终态盘点
        try (Connection c = dbPool.take()) {
// 写 C1 结果行
            write(String.format("| C1 同步落库(无MQ) | %d | %s | %dms | %dms（受理即全部落库） |",
// 参数续行：受理数/延迟/墙钟
                    r.success(), pct(r.latencies()), r.wallMs(), r.wallMs()));
// 盘点 try 块结束
        }
// C1 方法体结束
    }

// ===== 对照组 C2 =====
    /** C2 MQ 异步（生产方案）：Lua 预扣成功后发送 RabbitMQ 立即返回，单消费者异步落库。 */
// 对照组 C2：MQ 异步入口
    @Test
// 执行顺序 2
    @Order(2)
// C2 主体：重置→声明MQ→启动消费者→Lua+发消息→轮询落库
    void C2_mqAsync() throws Exception {
// 重置现场
        resetEnv();
// 声明 MQ 拓扑并清空队列
        declareMq();
        // 启动 1 个消费者（与生产 @RabbitListener 默认并发一致），复刻生产 createVoucherOrder 逻辑
// 创建消息监听容器
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
// 创建 MQ 连接工厂
        CachingConnectionFactory mqFactory = new CachingConnectionFactory(MQ_HOST, MQ_PORT);
// 设置用户名
        mqFactory.setUsername(MQ_USER);
// 设置密码
        mqFactory.setPassword(MQ_PASS);
// 注入连接工厂
        container.setConnectionFactory(mqFactory);
// 订阅订单队列
        container.setQueueNames(QUEUE);
// 消费端计数器（原子）
        AtomicInteger consumed = new AtomicInteger();
        // 消费端直接按 JSON body 解析（发送端是 Jackson JSON，消息体即 VoucherOrder 的 JSON）
// 注册消息监听器
        container.setMessageListener(message -> {
// try-catch 包裹消费逻辑
            try {
// JSON body 解析为订单对象
                VoucherOrder order = cn.hutool.json.JSONUtil.toBean(
// 解析续行：字符串化后转 Bean
                        new String(message.getBody(), StandardCharsets.UTF_8), VoucherOrder.class);
// 复用生产同款落库逻辑
                createVoucherOrder(order);
// 消费计数 +1
                consumed.incrementAndGet();
// 消费异常捕获
            } catch (Exception e) {
// 包装上抛（抛异常即 nack，消息会重投）
                throw new RuntimeException(e);
// catch 块结束
            }
// 监听器 lambda 结束
        });
// 启动消费者容器
        container.start();

// 起压：1000 线程并发执行 C2 动作
        RunResult r = runConcurrent(idx -> {
            // 与生产完全一致：Lua 预扣成功 → 组装订单 → 发送 MQ → 立即返回（不等落库）
// 第一步：Lua 资格判断+预扣
            Long res = redis.execute(script,
// keys 续行：库存 key + 一人一单集合
                    Arrays.asList("seckill:stock:" + VOUCHER_ID, "seckill:order:" + VOUCHER_ID),
// argv 续行：券 ID / 用户 ID
                    String.valueOf(VOUCHER_ID), String.valueOf(idx + 1),
// argv 续行：订单 ID（C2 段固定前缀）
                    String.valueOf(9_300_000_000_000_000L + idx));
// 未拿到资格则受理失败
            if (res == null || res != 0L) return false;
// 第二步：组装订单对象
            VoucherOrder order = new VoucherOrder();
// 设置订单 ID
            order.setId(9_300_000_000_000_000L + idx);
// 设置用户 ID
            order.setUserId((long) (idx + 1));
// 设置券 ID
            order.setVoucherId(VOUCHER_ID);
// 发送到 fanout 交换机后立即返回（不等落库）
            rabbit.convertAndSend(EXCHANGE, "", order);
// 受理成功返回
            return true;
// C2 压测动作 lambda 结束
        });

        // 轮询等待消费端全部落库完成，同时记录落库节奏（削峰曲线）
// 落库节奏采样曲线容器
        List<String> curve = new ArrayList<>();
// 落库等待起点（纳秒）
        long settleStart = System.nanoTime();
// 已落库订单计数
        int done = 0;
// 轮询上限 120 秒
        while (System.nanoTime() - settleStart < TimeUnit.SECONDS.toNanos(120)) {
// 每 200ms 采样一次
            Thread.sleep(200);
// 借连接查询订单数
            Connection c = dbPool.take();
// try-finally 保证归还
            try {
// 查询当前已落库订单数
                done = orderCount(c);
// finally：归还连接
            } finally {
// 归还连接（不能用 try-with-resources，会 close）
                dbPool.offer(c);                 // 归还连接（try-with-resources 会 close 连接，不能用）
// finally 块结束
            }
// 计算等待开始的相对耗时
            long elapsedMs = (System.nanoTime() - settleStart) / 1_000_000;
// 记录采样点：时间:已落库笔数
            curve.add(elapsedMs + "ms:" + done + "笔");
// 全部落库完成则提前退出轮询
            if (done >= r.success()) break;
// 轮询循环结束
        }
// 统计落库完成总时长
        long settleMs = (System.nanoTime() - settleStart) / 1_000_000;
// 停止消费者容器
        container.stop();

// 写 C2 结果行
        write(String.format("| C2 MQ异步(生产) | %d | %s | %dms（受理） | %dms（全部落库完成） |",
// 参数续行：受理数/延迟/墙钟/落库总时长
                r.success(), pct(r.latencies()), r.wallMs(), settleMs));
// 输出落库节奏曲线（削峰证据）
        write("|  └ 落库节奏: " + String.join(" → ", curve) + " |");
// 输出消费端统计与延迟口径说明
        write("|  └ 消费端实际消费 " + consumed.get() + " 笔；接口侧延迟不含落库，用户感知为受理即返回 |");
// C2 方法体结束
    }
// SeckillMqBenchmark 类结束
}
