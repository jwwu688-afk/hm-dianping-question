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
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lua 脚本 vs 无 Lua（Java 分步判断）并发正确性对比基准。
 *
 * 背景：无 Lua 的旧实现是「查库存 → 判一人一单 → 扣减」三步分开执行，
 * 步骤之间会被其他线程穿插（check-then-act 竞态窗口）。
 *
 * 四组对照（1000 并发 / 初始库存 100）：
 *   D1a 无Lua·多用户：1000 个不同用户抢 100 件 —— 看超卖（库存扣成负数、受理数超库存）
 *   D1b 无Lua·单用户：同一个用户发 1000 次请求     —— 看一人一单失效（重复下单）
 *   D2a Lua·多用户（生产）：同样的多用户场景
 *   D2b Lua·单用户（生产）：同样的单用户场景
 *
 * 「有效率」定义：符合业务规则的受理数 ÷ 实际受理数。
 *   多用户场景应受理 100（=库存）；单用户场景应受理 1（一人一单）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeckillCorrectnessBenchmark {

    static final long VOUCHER_ID = 10L;
    static final int THREADS = 1000;
    static final int INIT_STOCK = 100;
    static final long SAME_USER_ID = 99999L;     // D1b/D2b 单用户场景的用户 ID

    static final Path REPORT = Path.of("benchmark_out", "correctness_report.md");

    static StringRedisTemplate redis;
    static DefaultRedisScript<Long> script;

    @BeforeAll
    static void init() {
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        conf.setPassword(RedisPassword.of("123456"));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(conf);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
        try {
            Files.createDirectories(REPORT.getParent());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 重置 Redis 现场：库存预热、清空一人一单集合。 */
    static void reset() {
        redis.delete("seckill:stock:" + VOUCHER_ID);
        redis.delete("seckill:order:" + VOUCHER_ID);
        redis.opsForValue().set("seckill:stock:" + VOUCHER_ID, String.valueOf(INIT_STOCK));
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

    interface ThrowingIntAction {
        boolean apply(int idx) throws Exception;
    }

    static RunResult runConcurrent(int threads, ThrowingIntAction action) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long[] latencies = new long[threads];
        AtomicInteger accepted = new AtomicInteger();
        long start = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    barrier.await();
                    long t0 = System.nanoTime();
                    boolean ok = action.apply(idx);
                    latencies[idx] = System.nanoTime() - t0;
                    if (ok) accepted.incrementAndGet();
                } catch (Exception ignored) {
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.MINUTES);
        return new RunResult(accepted.get(), (System.nanoTime() - start) / 1_000_000, latencies);
    }

    record RunResult(int accepted, long wallMs, long[] latencies) {}

    /** 无 Lua 的旧实现：三步分开执行（查库存→判重复→扣减+记录），步骤间存在竞态窗口。 */
    boolean noLuaSeckill(long userId) {
        // ① 查库存并判断（此刻读到的值可能马上就被其他线程改掉）
        String stock = redis.opsForValue().get("seckill:stock:" + VOUCHER_ID);
        if (stock == null || Integer.parseInt(stock) <= 0) return false;
        // ② 判断一人一单（判断与记录之间存在竞态窗口）
        Boolean member = redis.opsForSet().isMember("seckill:order:" + VOUCHER_ID, String.valueOf(userId));
        if (Boolean.TRUE.equals(member)) return false;
        // ③ 扣减 + 记录（两个动作也不原子）
        redis.opsForValue().decrement("seckill:stock:" + VOUCHER_ID);
        redis.opsForSet().add("seckill:order:" + VOUCHER_ID, String.valueOf(userId));
        return true;
    }

    /** Lua 的生产实现：三步在单脚本内原子完成。 */
    boolean luaSeckill(long userId) {
        Long res = redis.execute(script,
                Arrays.asList("seckill:stock:" + VOUCHER_ID, "seckill:order:" + VOUCHER_ID),
                String.valueOf(VOUCHER_ID), String.valueOf(userId),
                String.valueOf(System.nanoTime()));
        return res != null && res == 0L;
    }

    static void writeResult(String name, RunResult r, int shouldAccept, int finalStock) {
        double validRate = r.accepted() == 0 ? 0 : 100.0 * shouldAccept / r.accepted();
        write(String.format("| %s | %d | %d | %d | %.1f%% | %dms | %s |",
                name, r.accepted(), shouldAccept, finalStock,
                Math.min(validRate, 100.0), r.wallMs(), pct(r.latencies())));
    }

    @BeforeAll
    static void reportHeader() {
        write("");
        write("## Lua vs 无 Lua 并发正确性消融（1000 并发 / 初始库存 100）");
        write("");
        write("| 方案 | 实际受理数 | 应受理数 | 终态库存 | 受理有效率 | 墙钟耗时 | 单请求延迟 |");
        write("|---|---|---|---|---|---|---|");
    }

    /** D1a 无 Lua · 多用户：应受理 100，竞态导致超发。 */
    @Test
    @Order(1)
    void D1a_noLuaMultiUser() throws Exception {
        reset();
        RunResult r = runConcurrent(THREADS, idx -> noLuaSeckill(idx + 1L));
        int finalStock = Integer.parseInt(
                String.valueOf(redis.opsForValue().get("seckill:stock:" + VOUCHER_ID)));
        writeResult("D1a 无Lua·多用户", r, INIT_STOCK, finalStock);
    }

    /** D1b 无 Lua · 单用户：1000 次请求同一用户，竞态导致重复下单全部放行。 */
    @Test
    @Order(2)
    void D1b_noLuaSameUser() throws Exception {
        reset();
        RunResult r = runConcurrent(THREADS, idx -> noLuaSeckill(SAME_USER_ID));
        int finalStock = Integer.parseInt(
                String.valueOf(redis.opsForValue().get("seckill:stock:" + VOUCHER_ID)));
        writeResult("D1b 无Lua·单用户(1000次重复请求)", r, 1, finalStock);
    }

    /** D2a Lua · 多用户（生产）：应受理 100，恰好 100。 */
    @Test
    @Order(3)
    void D2a_luaMultiUser() throws Exception {
        reset();
        RunResult r = runConcurrent(THREADS, idx -> luaSeckill(idx + 1L));
        int finalStock = Integer.parseInt(
                String.valueOf(redis.opsForValue().get("seckill:stock:" + VOUCHER_ID)));
        writeResult("D2a Lua·多用户(生产)", r, INIT_STOCK, finalStock);
    }

    /** D2b Lua · 单用户（生产）：应受理 1，恰好 1。 */
    @Test
    @Order(4)
    void D2b_luaSameUser() throws Exception {
        reset();
        RunResult r = runConcurrent(THREADS, idx -> luaSeckill(SAME_USER_ID));
        int finalStock = Integer.parseInt(
                String.valueOf(redis.opsForValue().get("seckill:stock:" + VOUCHER_ID)));
        writeResult("D2b Lua·单用户(1000次重复请求)", r, 1, finalStock);
    }
}
