// 包声明：缓存击穿消融压测所在包
package com.hmdp.benchmark;

// Shop 实体：热点缓存对象类型（tb_shop）
import com.hmdp.entity.Shop;
// CacheClient：生产缓存工具类，三组对照均直接调用它
import com.hmdp.utils.CacheClient;
// BeforeAll：静态初始化注解
import org.junit.jupiter.api.BeforeAll;
// MethodOrderer：测试排序器接口
import org.junit.jupiter.api.MethodOrderer;
// Order：顺序注解
import org.junit.jupiter.api.Order;
// Test：测试方法注解
import org.junit.jupiter.api.Test;
// TestMethodOrder：声明按注解值排序
import org.junit.jupiter.api.TestMethodOrder;
// RedisPassword：Redis 密码值对象
import org.springframework.data.redis.connection.RedisPassword;
// RedisStandaloneConfiguration：单机 Redis 连接配置
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
// LettuceConnectionFactory：Lettuce 连接工厂
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
// StringRedisTemplate：Redis 字符串操作模板
import org.springframework.data.redis.core.StringRedisTemplate;

// StandardCharsets：UTF-8 字符集常量
import java.nio.charset.StandardCharsets;
// Files：文件读写工具类
import java.nio.file.Files;
// Path：文件路径对象
import java.nio.file.Path;
// StandardOpenOption：文件打开选项（创建/追加）
import java.nio.file.StandardOpenOption;
// Connection：数据库物理连接
import java.sql.Connection;
// DriverManager：JDBC 建连管理器
import java.sql.DriverManager;
// PreparedStatement：预编译 SQL 语句
import java.sql.PreparedStatement;
// ResultSet：查询结果集游标
import java.sql.ResultSet;
// ArrayList：动态数组列表
import java.util.ArrayList;
// Arrays：数组工具类
import java.util.Arrays;
// BlockingQueue：阻塞队列（模拟 DB 连接池）
import java.util.concurrent.BlockingQueue;
// CyclicBarrier：循环栅栏（并发对齐）
import java.util.concurrent.CyclicBarrier;
// ExecutorService：线程池服务
import java.util.concurrent.ExecutorService;
// Executors：线程池工厂
import java.util.concurrent.Executors;
// LinkedBlockingQueue：链表阻塞队列
import java.util.concurrent.LinkedBlockingQueue;
// TimeUnit：时间单位枚举
import java.util.concurrent.TimeUnit;
// AtomicInteger：原子整型计数器
import java.util.concurrent.atomic.AtomicInteger;

// ===== 类级实验设计说明：以下原 Javadoc 保留 =====
/**
 * 缓存击穿防护消融压测基准：热点 key（店铺 1）失效瞬间，500 线程同时涌入。
 *
 * 三组对照（全部直接调用生产 CacheClient，保证测的是线上逻辑而非模拟实现）：
 *   B1 无防护（queryWithPassThrough）：所有未命中线程都回源 DB —— 击穿基线
 *   B2 互斥锁（queryWithMutex）：SETNX 抢锁，仅 1 线程回源重建，其余自旋等待 —— 一致性优先
 *   B3 逻辑过期（queryWithLogicalExpire）：过期数据照常返回，后台单线程异步重建 —— 可用性优先
 *
 * 指标：DB 回源次数 / 全部线程返回耗时 / 单请求延迟 p50/p95（B3 额外记录「拿到的是否旧数据」）。
 */
// 按 @Order 顺序执行：B1 → B2 → B3
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// 缓存击穿消融压测类（包级私有）
class CacheBreakdownBenchmark {

// ===== 压测参数区 =====
    static final long SHOP_ID = 1L;              // 热点店铺 ID（tb_shop 中已有）
// 并发模型：500 线程在热点 key 失效瞬间同时涌入
    static final int THREADS = 500;              // 击穿瞬间并发线程数
// 控制变量：DB 连接池规格三组一致
    static final int DB_POOL_SIZE = 20;          // 模拟生产数据库连接池大小

// 本机 MySQL 连接串
    static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
// 数据库账号
    static final String DB_USER = "root";
// 数据库密码
    static final String DB_PASS = "123456";

// 缓存 key 前缀（与生产 CacheClient 用法一致）
    static final String KEY_PREFIX = "cache:shop:";
// 报告输出路径
    static final Path REPORT = Path.of("benchmark_out", "cache_report.md");

// 自建 DB 连接池
    static BlockingQueue<Connection> dbPool;
// 生产 CacheClient 实例
    static CacheClient cacheClient;
// Redis 模板
    static StringRedisTemplate redis;

// 生命周期：类加载后执行一次
    @BeforeAll
// 初始化连接池与生产 CacheClient
    static void init() throws Exception {
// 建 20 连接的自建池
        dbPool = new LinkedBlockingQueue<>();
// 循环建连
        for (int i = 0; i < DB_POOL_SIZE; i++) {
// 连接入池
            dbPool.offer(DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS));
// 循环结束
        }
        // 直接构造生产 CacheClient（与 Spring 注入等价的 StringRedisTemplate）
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
// 构建生产 CacheClient（测的是线上逻辑而非模拟实现）
        cacheClient = new CacheClient(redis);
// 确保报告目录存在
        Files.createDirectories(REPORT.getParent());
// init 方法结束
    }

    /** 生产风格的 DB 回源函数：查 tb_shop 并计数（计数器就是「DB 被打了几次」的直接证据）。 */
// DB_HITS：回源次数计数器（B 组指标核心）
    static final AtomicInteger DB_HITS = new AtomicInteger();
    /** 回源时间线：记录每次 DB 回源的相对时刻(ms)与线程名，用于分析多线程穿透的时序模式。 */
// FALLBACK_TIMELINE：回源时间线容器
    static final java.util.concurrent.ConcurrentLinkedQueue<String> FALLBACK_TIMELINE = new java.util.concurrent.ConcurrentLinkedQueue<>();
// 回源计时基准（纳秒起点）
    static long T0 = 0;

// DB 回源函数实现：每次调用即一次真实击穿
    Shop dbFallback(Long id) {
// 回源计数 +1 并取当前值
        int n = DB_HITS.incrementAndGet();
// 记录回源时间线：线程名@相对毫秒
        FALLBACK_TIMELINE.offer(Thread.currentThread().getName() + "@" + (System.nanoTime() - T0) / 1_000_000 + "ms");
// 连接引用（finally 中归还）
        Connection c = null;
// try-finally 保证连接归还
        try {
// 从池中借连接
            c = dbPool.take();                    // 从池中取连接
            // 注意：不能把 executeQuery 写进 try-with-resources 的资源初始化，
            // 否则会在 setLong 绑定参数之前执行，抛「No value specified for parameter 1」
// 创建查询店铺的预编译语句
            try (PreparedStatement ps = c.prepareStatement(
// SELECT 文本：按主键查 tb_shop
                    "SELECT id, name, address FROM tb_shop WHERE id = ?")) {
// 绑定店铺 ID 参数
                ps.setLong(1, id);
// 执行查询
                try (ResultSet rs = ps.executeQuery()) {
// 游标移到结果集首行
                    rs.next();
// 组装 Shop 实体
                    Shop shop = new Shop();
// 回填主键
                    shop.setId(rs.getLong("id"));
// 回填店铺名
                    shop.setName(rs.getString("name"));
// 回填地址
                    shop.setAddress(rs.getString("address"));
// 返回回源结果
                    return shop;
// ResultSet 资源块结束
                }
// PreparedStatement 资源块结束
            }
// 查询异常捕获
        } catch (Exception e) {
// 包装为运行时异常上抛
            throw new RuntimeException(e);
// finally：归还连接
        } finally {
// 归还连接（不能 close，否则耗尽池）
            if (c != null) dbPool.offer(c);       // 归还连接（不能 close，close 会耗尽连接池）
// finally 块结束
        }
// dbFallback 方法结束
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
// 包装上抛终止压测
            throw new RuntimeException(e);
// catch 块结束
        }
// write 方法结束
    }

// 延迟百分位计算（p50/p95）
    static String pct(long[] latencies) {
// 样本升序排序
        Arrays.sort(latencies);
// 样本总数
        int n = latencies.length;
// 格式化输出 p50/p95
        return String.format("p50=%.1fms p95=%.1fms", latencies[n / 2] / 1e6, latencies[(int) (n * 0.95)] / 1e6);
// 下标取值与纳秒转毫秒续行
    }
// pct 方法结束

    record RunResult(long wallMs, long[] latencies) {}
// 结果记录：墙钟毫秒 + 逐线程延迟

    /** 栅栏对齐并发执行：500 线程同一瞬间发起，模拟热点 key 失效瞬间的请求风暴。 */
    static RunResult runConcurrent(java.util.function.IntFunction<Object> action) throws Exception {
// 栅栏对齐并发执行框架
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
// 栅栏：500 线程到齐后同时放行
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
// 固定大小线程池
        long[] latencies = new long[THREADS];
// 逐线程延迟样本数组
        FALLBACK_TIMELINE.clear();
// 清空上一轮回源时间线
        T0 = System.nanoTime();
// 记录计时基准 T0
        long start = T0;
// 墙钟起点与 T0 对齐
        for (int i = 0; i < THREADS; i++) {
// 提交 500 个虚拟用户任务
            final int idx = i;
// 线程编号捕获（effectively final）
            pool.submit(() -> {
// 提交单个任务
                try {
// 任务体：对齐→计时→执行
                    barrier.await();
// 栅栏对齐：同一瞬间发起
                    long t0 = System.nanoTime();
// 请求开始时刻
                    action.apply(idx);
// 执行被测的缓存查询
                    latencies[idx] = System.nanoTime() - t0;
// 记录逐线程耗时
                } catch (Exception ignored) {
// 异常捕获：单线程失败忽略
                }
// catch 块结束
            });
// lambda 结束
        }
// 提交循环结束
        pool.shutdown();
// 不再接收新任务
        try {
// 等待全部线程终止
            if (!pool.awaitTermination(5, TimeUnit.MINUTES)) {
// 超时判定：5 分钟未结束则强杀
                pool.shutdownNow();               // 超时强杀，避免单场景挂死拖垮整轮
// 超时强杀兜底
            }
// if 结束
        } catch (InterruptedException e) {
// 中断兜底分支
            pool.shutdownNow();
// 强制关闭线程池
        }
// try-catch 结束
        return new RunResult((System.nanoTime() - start) / 1_000_000, latencies);
// 打包返回墙钟与延迟样本
    }
// runConcurrent 方法结束

    @BeforeAll
// 生命周期：写报告表头
    static void reportHeader() {
// reportHeader 方法体
        write("");
// 空行分隔
        write("## 缓存击穿防护消融（热点店铺 " + SHOP_ID + " / " + THREADS + " 并发瞬时涌入 / 连接池 " + DB_POOL_SIZE + "）");
// 压测参数概览标题
        write("");
// 空行
        write("| 方案 | DB 回源次数 | 全部线程返回耗时 | 单请求延迟 | 说明 |");
// 结果表列头
        write("|---|---|---|---|---|");
// Markdown 分隔行
    }
// reportHeader 方法结束

    /** B1 无防护：queryWithPassThrough——缓存失效后所有线程都打 DB。 */
    @Test
// 对照组 B1：无防护基线入口
    @Order(1)
// 执行顺序 1
    void B1_noProtection() throws Exception {
// B1 主体：重置→栅栏→并发穿透→统计
        redis.delete(KEY_PREFIX + SHOP_ID);          // 模拟热点 key 已失效
// 模拟热点 key 已失效
        DB_HITS.set(0);
// 回源计数清零
        RunResult r = runConcurrent(idx -> cacheClient.queryWithPassThrough(
// 起压：500 线程并发调用无防护查询
                KEY_PREFIX, SHOP_ID, Shop.class, this::dbFallback, 30L, TimeUnit.MINUTES));
// 参数续行：直调 queryWithPassThrough
        Thread.sleep(500);                            // 等可能的尾部写入完成
// 等待可能的尾部写入完成
        write(String.format("| B1 无防护 | %d | %dms | %s | 每个未命中线程都回源 |",
// 写 B1 结果行
                DB_HITS.get(), r.wallMs(), pct(r.latencies())));
// 参数续行：回源数/墙钟/延迟
    }
// B1 方法体结束

    /** B2 互斥锁：queryWithMutex——只有抢到锁的 1 个线程回源，其余自旋等缓存就绪。 */
    @Test
// 对照组 B2：互斥锁入口
    @Order(2)
// 执行顺序 2
    void B2_mutex() throws Exception {
// B2 主体：重置→栅栏→并发互斥重建→统计
        redis.delete(KEY_PREFIX + SHOP_ID);
// 删除缓存
        redis.delete("lock:shop:" + SHOP_ID);        // 清理可能的残留锁
// 清理可能的残留锁
        DB_HITS.set(0);
// 回源计数清零
        RunResult r = runConcurrent(idx -> cacheClient.queryWithMutex(
// 起压：互斥锁版本查询
                KEY_PREFIX, SHOP_ID, Shop.class, this::dbFallback, 30L, TimeUnit.MINUTES));
// 参数续行：直调 queryWithMutex
        Thread.sleep(500);
// 等待尾部写入
        write(String.format("| B2 互斥锁 | %d | %dms | %s | 一致性优先：仅1线程重建,其余等缓存 |",
// 写 B2 结果行
                DB_HITS.get(), r.wallMs(), pct(r.latencies())));
// 参数续行
        writeB2Timeline();
// 输出回源时间线（时序证据）
    }
// B2 方法体结束

    /** 输出回源时间线前 40 条，分析「互斥锁为何多次穿透」的时序证据。 */
    static void writeB2Timeline() {
// 时间线输出工具方法
        var list = new ArrayList<>(FALLBACK_TIMELINE);
// 拷贝时间线快照
        if (!list.isEmpty()) {
// 非空才输出
            write("|  └ 回源时间线(前40条): " + String.join(", ",
// 拼接前 40 条时间线
                    list.subList(0, Math.min(40, list.size()))) + " |");
// 截断与拼接续行
        }
// if 结束
    }
// writeB2Timeline 方法结束

    /** B3 逻辑过期：queryWithLogicalExpire——预先写入已逻辑过期的缓存，全部请求拿旧数据秒回。 */
    @Test
// 对照组 B3：逻辑过期入口
    @Order(3)
// 执行顺序 3
    void B3_logicalExpire() throws Exception {
// B3 主体：预写过期缓存→栅栏→秒回旧数据→统计
        redis.delete(KEY_PREFIX + SHOP_ID);
// 删除缓存（配合预写过期数据）
        DB_HITS.set(0);
// 回源计数清零
        // 预写一条已逻辑过期 10 秒的缓存（模拟「热点 key 逻辑过期但物理仍在」的场景）
        cacheClient.setWithLogicalExpire(KEY_PREFIX + SHOP_ID,
// 预写一条已逻辑过期 10 秒的缓存
                dbFallback(SHOP_ID), -10L, TimeUnit.SECONDS);
// 参数续行：值为回源结果，提前 10 秒过期
        DB_HITS.set(0);                              // 预写不计入击穿统计
// 预写操作不计入击穿统计
        RunResult r = runConcurrent(idx -> cacheClient.queryWithLogicalExpire(
// 起压：逻辑过期版本查询
                KEY_PREFIX, SHOP_ID, Shop.class, this::dbFallback, 30L, TimeUnit.MINUTES));
// 参数续行：直调 queryWithLogicalExpire
        Thread.sleep(2000);                          // 等异步重建线程完成，再统计回源次数
// 等待异步重建线程完成再统计
        write(String.format("| B3 逻辑过期 | %d | %dms | %s | 可用性优先：全部请求秒回旧数据,后台重建1次 |",
// 写 B3 结果行
                DB_HITS.get(), r.wallMs(), pct(r.latencies())));
// 参数续行
        write("|  └ B3 各请求返回的是旧数据（重建完成前），业务需接受短暂不一致 |");
// 附加说明：重建完成前返回旧数据
    }
// B3 方法体结束
}
// CacheBreakdownBenchmark 类结束
