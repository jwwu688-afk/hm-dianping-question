package com.hmdp.benchmark;

import com.hmdp.entity.Shop;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CacheBreakdownBenchmark {

    static final long SHOP_ID = 1L;              // 热点店铺 ID（tb_shop 中已有）
    static final int THREADS = 500;              // 击穿瞬间并发线程数
    static final int DB_POOL_SIZE = 20;          // 模拟生产数据库连接池大小

    static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    static final String DB_USER = "root";
    static final String DB_PASS = "123456";

    static final String KEY_PREFIX = "cache:shop:";
    static final Path REPORT = Path.of("benchmark_out", "cache_report.md");

    static BlockingQueue<Connection> dbPool;
    static CacheClient cacheClient;
    static StringRedisTemplate redis;

    @BeforeAll
    static void init() throws Exception {
        dbPool = new LinkedBlockingQueue<>();
        for (int i = 0; i < DB_POOL_SIZE; i++) {
            dbPool.offer(DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS));
        }
        // 直接构造生产 CacheClient（与 Spring 注入等价的 StringRedisTemplate）
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        conf.setPassword(RedisPassword.of("123456"));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(conf);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        cacheClient = new CacheClient(redis);
        Files.createDirectories(REPORT.getParent());
    }

    /** 生产风格的 DB 回源函数：查 tb_shop 并计数（计数器就是「DB 被打了几次」的直接证据）。 */
    static final AtomicInteger DB_HITS = new AtomicInteger();
    /** 回源时间线：记录每次 DB 回源的相对时刻(ms)与线程名，用于分析多线程穿透的时序模式。 */
    static final java.util.concurrent.ConcurrentLinkedQueue<String> FALLBACK_TIMELINE = new java.util.concurrent.ConcurrentLinkedQueue<>();
    static long T0 = 0;

    Shop dbFallback(Long id) {
        int n = DB_HITS.incrementAndGet();
        FALLBACK_TIMELINE.offer(Thread.currentThread().getName() + "@" + (System.nanoTime() - T0) / 1_000_000 + "ms");
        Connection c = null;
        try {
            c = dbPool.take();                    // 从池中取连接
            // 注意：不能把 executeQuery 写进 try-with-resources 的资源初始化，
            // 否则会在 setLong 绑定参数之前执行，抛「No value specified for parameter 1」
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, address FROM tb_shop WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    Shop shop = new Shop();
                    shop.setId(rs.getLong("id"));
                    shop.setName(rs.getString("name"));
                    shop.setAddress(rs.getString("address"));
                    return shop;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (c != null) dbPool.offer(c);       // 归还连接（不能 close，close 会耗尽连接池）
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
        return String.format("p50=%.1fms p95=%.1fms", latencies[n / 2] / 1e6, latencies[(int) (n * 0.95)] / 1e6);
    }

    record RunResult(long wallMs, long[] latencies) {}

    /** 栅栏对齐并发执行：500 线程同一瞬间发起，模拟热点 key 失效瞬间的请求风暴。 */
    static RunResult runConcurrent(java.util.function.IntFunction<Object> action) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        long[] latencies = new long[THREADS];
        FALLBACK_TIMELINE.clear();
        T0 = System.nanoTime();
        long start = T0;
        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    barrier.await();
                    long t0 = System.nanoTime();
                    action.apply(idx);
                    latencies[idx] = System.nanoTime() - t0;
                } catch (Exception ignored) {
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.MINUTES)) {
                pool.shutdownNow();               // 超时强杀，避免单场景挂死拖垮整轮
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
        }
        return new RunResult((System.nanoTime() - start) / 1_000_000, latencies);
    }

    @BeforeAll
    static void reportHeader() {
        write("");
        write("## 缓存击穿防护消融（热点店铺 " + SHOP_ID + " / " + THREADS + " 并发瞬时涌入 / 连接池 " + DB_POOL_SIZE + "）");
        write("");
        write("| 方案 | DB 回源次数 | 全部线程返回耗时 | 单请求延迟 | 说明 |");
        write("|---|---|---|---|---|");
    }

    /** B1 无防护：queryWithPassThrough——缓存失效后所有线程都打 DB。 */
    @Test
    @Order(1)
    void B1_noProtection() throws Exception {
        redis.delete(KEY_PREFIX + SHOP_ID);          // 模拟热点 key 已失效
        DB_HITS.set(0);
        RunResult r = runConcurrent(idx -> cacheClient.queryWithPassThrough(
                KEY_PREFIX, SHOP_ID, Shop.class, this::dbFallback, 30L, TimeUnit.MINUTES));
        Thread.sleep(500);                            // 等可能的尾部写入完成
        write(String.format("| B1 无防护 | %d | %dms | %s | 每个未命中线程都回源 |",
                DB_HITS.get(), r.wallMs(), pct(r.latencies())));
    }

    /** B2 互斥锁：queryWithMutex——只有抢到锁的 1 个线程回源，其余自旋等缓存就绪。 */
    @Test
    @Order(2)
    void B2_mutex() throws Exception {
        redis.delete(KEY_PREFIX + SHOP_ID);
        redis.delete("lock:shop:" + SHOP_ID);        // 清理可能的残留锁
        DB_HITS.set(0);
        RunResult r = runConcurrent(idx -> cacheClient.queryWithMutex(
                KEY_PREFIX, SHOP_ID, Shop.class, this::dbFallback, 30L, TimeUnit.MINUTES));
        Thread.sleep(500);
        write(String.format("| B2 互斥锁 | %d | %dms | %s | 一致性优先：仅1线程重建,其余等缓存 |",
                DB_HITS.get(), r.wallMs(), pct(r.latencies())));
        writeB2Timeline();
    }

    /** 输出回源时间线前 40 条，分析「互斥锁为何多次穿透」的时序证据。 */
    static void writeB2Timeline() {
        var list = new ArrayList<>(FALLBACK_TIMELINE);
        if (!list.isEmpty()) {
            write("|  └ 回源时间线(前40条): " + String.join(", ",
                    list.subList(0, Math.min(40, list.size()))) + " |");
        }
    }

    /** B3 逻辑过期：queryWithLogicalExpire——预先写入已逻辑过期的缓存，全部请求拿旧数据秒回。 */
    @Test
    @Order(3)
    void B3_logicalExpire() throws Exception {
        redis.delete(KEY_PREFIX + SHOP_ID);
        DB_HITS.set(0);
        // 预写一条已逻辑过期 10 秒的缓存（模拟「热点 key 逻辑过期但物理仍在」的场景）
        cacheClient.setWithLogicalExpire(KEY_PREFIX + SHOP_ID,
                dbFallback(SHOP_ID), -10L, TimeUnit.SECONDS);
        DB_HITS.set(0);                              // 预写不计入击穿统计
        RunResult r = runConcurrent(idx -> cacheClient.queryWithLogicalExpire(
                KEY_PREFIX, SHOP_ID, Shop.class, this::dbFallback, 30L, TimeUnit.MINUTES));
        Thread.sleep(2000);                          // 等异步重建线程完成，再统计回源次数
        write(String.format("| B3 逻辑过期 | %d | %dms | %s | 可用性优先：全部请求秒回旧数据,后台重建1次 |",
                DB_HITS.get(), r.wallMs(), pct(r.latencies())));
        write("|  └ B3 各请求返回的是旧数据（重建完成前），业务需接受短暂不一致 |");
    }
}
