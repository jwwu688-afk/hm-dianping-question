// 包声明：秒杀正确性消融压测所在包
package com.hmdp.benchmark;

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
// ClassPathResource：加载 seckill.lua 脚本
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
// Arrays：数组工具与 asList
import java.util.Arrays;
// CyclicBarrier：循环栅栏（并发对齐）
import java.util.concurrent.CyclicBarrier;
// ExecutorService：线程池服务
import java.util.concurrent.ExecutorService;
// Executors：线程池工厂
import java.util.concurrent.Executors;
// TimeUnit：时间单位
import java.util.concurrent.TimeUnit;
// AtomicInteger：原子计数器
import java.util.concurrent.atomic.AtomicInteger;

// ===== 类级实验设计说明：以下原 Javadoc 保留 =====
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
// 按 @Order 顺序执行：D1a → D1b → D2a → D2b
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// 正确性消融压测类（包级私有）
class SeckillCorrectnessBenchmark {

// ===== 压测参数区 =====
    static final long VOUCHER_ID = 10L;
// 并发模型：1000 线程栅栏对齐同时发起
    static final int THREADS = 1000;
// 初始库存：100 件
    static final int INIT_STOCK = 100;
// 单用户场景固定用户 ID
    static final long SAME_USER_ID = 99999L;     // D1b/D2b 单用户场景的用户 ID

// 报告输出路径
    static final Path REPORT = Path.of("benchmark_out", "correctness_report.md");

// Redis 操作模板
    static StringRedisTemplate redis;
// Lua 脚本执行器
    static DefaultRedisScript<Long> script;

// 生命周期：初始化
    @BeforeAll
// init 方法体
    static void init() {
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
// 目录创建异常保护
        try {
// 确保报告目录存在
            Files.createDirectories(REPORT.getParent());
// 异常捕获
        } catch (Exception e) {
// 包装上抛
            throw new RuntimeException(e);
// catch 块结束
        }
// init 方法结束
    }

    /** 重置 Redis 现场：库存预热、清空一人一单集合。 */
// 重置 Redis 现场方法体
    static void reset() {
// 删除库存 key
        redis.delete("seckill:stock:" + VOUCHER_ID);
// 删除一人一单集合
        redis.delete("seckill:order:" + VOUCHER_ID);
// 预热库存到初始值
        redis.opsForValue().set("seckill:stock:" + VOUCHER_ID, String.valueOf(INIT_STOCK));
// reset 方法结束
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

// 延迟百分位计算（p50/p95）
    static String pct(long[] latencies) {
// 样本升序排序
        Arrays.sort(latencies);
// 样本总数
        int n = latencies.length;
// 格式化输出 p50/p95
        return String.format("p50=%.1fms p95=%.1fms", latencies[n / 2] / 1e6, latencies[(int) (n * 0.95)] / 1e6);
// pct 方法结束
    }

// 函数式接口：压测动作统一签名
    interface ThrowingIntAction {
// apply：入参线程编号，返回是否受理
        boolean apply(int idx) throws Exception;
// 接口结束
    }

// 栅栏对齐并发执行框架
    static RunResult runConcurrent(int threads, ThrowingIntAction action) throws Exception {
// 栅栏：1000 线程到齐后同时放行
        CyclicBarrier barrier = new CyclicBarrier(threads);
// 固定大小线程池
        ExecutorService pool = Executors.newFixedThreadPool(threads);
// 逐线程延迟样本数组
        long[] latencies = new long[threads];
// 受理计数器（原子）
        AtomicInteger accepted = new AtomicInteger();
// 墙钟起点
        long start = System.nanoTime();
// 提交任务循环
        for (int i = 0; i < threads; i++) {
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
// 执行被测动作并记录受理结果
                    boolean ok = action.apply(idx);
// 记录逐线程耗时
                    latencies[idx] = System.nanoTime() - t0;
// 受理成功则累加
                    if (ok) accepted.incrementAndGet();
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
        return new RunResult(accepted.get(), (System.nanoTime() - start) / 1_000_000, latencies);
// runConcurrent 方法结束
    }

// 结果记录：受理数 + 墙钟 + 逐线程延迟
    record RunResult(int accepted, long wallMs, long[] latencies) {}

    /** 无 Lua 的旧实现：三步分开执行（查库存→判重复→扣减+记录），步骤间存在竞态窗口。 */
// 无 Lua 旧实现方法体（对照组 D1 被测对象）
    boolean noLuaSeckill(long userId) {
        // ① 查库存并判断（此刻读到的值可能马上就被其他线程改掉）
// 步骤①实现：GET 库存（check-then-act 的 check）
        String stock = redis.opsForValue().get("seckill:stock:" + VOUCHER_ID);
// 库存不足直接拒绝
        if (stock == null || Integer.parseInt(stock) <= 0) return false;
        // ② 判断一人一单（判断与记录之间存在竞态窗口）
// 步骤②实现：SISMEMBER 判一人一单
        Boolean member = redis.opsForSet().isMember("seckill:order:" + VOUCHER_ID, String.valueOf(userId));
// 已下单则拒绝
        if (Boolean.TRUE.equals(member)) return false;
        // ③ 扣减 + 记录（两个动作也不原子）
// 步骤③实现：DECR 扣减（act，与 check 不原子）
        redis.opsForValue().decrement("seckill:stock:" + VOUCHER_ID);
// SADD 记录一人一单
        redis.opsForSet().add("seckill:order:" + VOUCHER_ID, String.valueOf(userId));
// 受理成功返回
        return true;
// noLuaSeckill 方法结束
    }

    /** Lua 的生产实现：三步在单脚本内原子完成。 */
// Lua 生产实现方法体（对照组 D2 被测对象）
    boolean luaSeckill(long userId) {
// 执行 Lua：keys=库存+一人一单集合
        Long res = redis.execute(script,
// keys 参数续行
                Arrays.asList("seckill:stock:" + VOUCHER_ID, "seckill:order:" + VOUCHER_ID),
// argv 续行：券 ID / 用户 ID
                String.valueOf(VOUCHER_ID), String.valueOf(userId),
// argv 续行：订单号（用 nanoTime 占位）
                String.valueOf(System.nanoTime()));
// 返回 0 = 拿到资格
        return res != null && res == 0L;
// luaSeckill 方法结束
    }

// 结果行写入工具
    static void writeResult(String name, RunResult r, int shouldAccept, int finalStock) {
// 受理有效率 = 应受理数 ÷ 实际受理数 × 100
        double validRate = r.accepted() == 0 ? 0 : 100.0 * shouldAccept / r.accepted();
// 写结果行
        write(String.format("| %s | %d | %d | %d | %.1f%% | %dms | %s |",
// 参数续行：名称与计数
                name, r.accepted(), shouldAccept, finalStock,
// 参数续行：有效率/墙钟/延迟
                Math.min(validRate, 100.0), r.wallMs(), pct(r.latencies())));
// writeResult 方法结束
    }

    @BeforeAll
// 生命周期：写报告表头
    static void reportHeader() {
// 空行分隔
        write("");
// 压测概览标题
        write("## Lua vs 无 Lua 并发正确性消融（1000 并发 / 初始库存 100）");
// 空行
        write("");
// 结果表列头
        write("| 方案 | 实际受理数 | 应受理数 | 终态库存 | 受理有效率 | 墙钟耗时 | 单请求延迟 |");
// Markdown 分隔行
        write("|---|---|---|---|---|---|---|");
// reportHeader 方法结束
    }

    /** D1a 无 Lua · 多用户：应受理 100，竞态导致超发。 */
// 对照组 D1a：无 Lua 多用户入口
    @Test
// 执行顺序 1
    @Order(1)
// D1a 主体：重置→栅栏→并发无 Lua 抢购
    void D1a_noLuaMultiUser() throws Exception {
// 重置现场
        reset();
// 起压：1000 个不同用户各抢一次
        RunResult r = runConcurrent(THREADS, idx -> noLuaSeckill(idx + 1L));
// 读取 Redis 终态库存
        int finalStock = Integer.parseInt(
// 续行
                String.valueOf(redis.opsForValue().get("seckill:stock:" + VOUCHER_ID)));
// 写结果行（应受理 = 库存 100）
        writeResult("D1a 无Lua·多用户", r, INIT_STOCK, finalStock);
// D1a 方法体结束
    }

    /** D1b 无 Lua · 单用户：1000 次请求同一用户，竞态导致重复下单全部放行。 */
// 对照组 D1b：无 Lua 单用户入口
    @Test
// 执行顺序 2
    @Order(2)
// D1b 主体：同一用户 1000 次重复请求
    void D1b_noLuaSameUser() throws Exception {
// 重置现场
        reset();
// 起压：单用户重复抢购
        RunResult r = runConcurrent(THREADS, idx -> noLuaSeckill(SAME_USER_ID));
// 读取 Redis 终态库存
        int finalStock = Integer.parseInt(
// 续行
                String.valueOf(redis.opsForValue().get("seckill:stock:" + VOUCHER_ID)));
// 写结果行（应受理 = 1，一人一单）
        writeResult("D1b 无Lua·单用户(1000次重复请求)", r, 1, finalStock);
// D1b 方法体结束
    }

    /** D2a Lua · 多用户（生产）：应受理 100，恰好 100。 */
// 对照组 D2a：Lua 多用户（生产）入口
    @Test
// 执行顺序 3
    @Order(3)
// D2a 主体：Lua 原子版多用户场景
    void D2a_luaMultiUser() throws Exception {
// 重置现场
        reset();
// 起压
        RunResult r = runConcurrent(THREADS, idx -> luaSeckill(idx + 1L));
// 读取 Redis 终态库存
        int finalStock = Integer.parseInt(
// 续行
                String.valueOf(redis.opsForValue().get("seckill:stock:" + VOUCHER_ID)));
// 写结果行（期望恰好 100）
        writeResult("D2a Lua·多用户(生产)", r, INIT_STOCK, finalStock);
// D2a 方法体结束
    }

    /** D2b Lua · 单用户（生产）：应受理 1，恰好 1。 */
// 对照组 D2b：Lua 单用户（生产）入口
    @Test
// 执行顺序 4
    @Order(4)
// D2b 主体：Lua 原子版单用户场景
    void D2b_luaSameUser() throws Exception {
// 重置现场
        reset();
// 起压
        RunResult r = runConcurrent(THREADS, idx -> luaSeckill(SAME_USER_ID));
// 读取 Redis 终态库存
        int finalStock = Integer.parseInt(
// 续行
                String.valueOf(redis.opsForValue().get("seckill:stock:" + VOUCHER_ID)));
// 写结果行（期望恰好 1）
        writeResult("D2b Lua·单用户(1000次重复请求)", r, 1, finalStock);
// D2b 方法体结束
    }
// SeckillCorrectnessBenchmark 类结束
}
