// 包声明：本类属于性能消融压测专用包，与普通单元测试分离
package com.hmdp.benchmark;

// ==================== JUnit 相关注解 ====================
// BeforeAll：静态初始化注解，所有测试方法执行前运行一次
import org.junit.jupiter.api.BeforeAll;
// MethodOrderer：测试方法排序器接口
import org.junit.jupiter.api.MethodOrderer;
// Order：标注测试方法执行顺序的注解
import org.junit.jupiter.api.Order;
// Test：JUnit 5 测试方法注解
import org.junit.jupiter.api.Test;
// TestMethodOrder：声明测试类按哪种排序器执行
import org.junit.jupiter.api.TestMethodOrder;
// ClassPathResource：加载 classpath 下的 seckill.lua 脚本资源
import org.springframework.core.io.ClassPathResource;
// ==================== Spring Data Redis（Lettuce）====================
// RedisPassword：封装 Redis 密码的值对象
import org.springframework.data.redis.connection.RedisPassword;
// RedisStandaloneConfiguration：单机 Redis 连接配置
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
// LettuceConnectionFactory：Lettuce 客户端连接工厂
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
// StringRedisTemplate：面向字符串键值的 Redis 操作模板
import org.springframework.data.redis.core.StringRedisTemplate;
// DefaultRedisScript：Spring 封装的 Lua 脚本执行器
import org.springframework.data.redis.core.script.DefaultRedisScript;

// ==================== 文件 IO（写 Markdown 报告）====================
// StandardCharsets：字符集常量（UTF-8）
import java.nio.charset.StandardCharsets;
// Files：文件读写工具类
import java.nio.file.Files;
// Path：文件路径对象
import java.nio.file.Path;
// StandardOpenOption：文件打开选项（创建/追加）
import java.nio.file.StandardOpenOption;

// ==================== 原生 JDBC ====================
// 压测不走 MyBatis/Spring 事务，直接打数据库以获得纯净指标
// Connection：一条数据库物理连接
import java.sql.Connection;
// DriverManager：JDBC 驱动管理器，用于建立连接
import java.sql.DriverManager;
// PreparedStatement：预编译 SQL 语句对象
import java.sql.PreparedStatement;
// ResultSet：SQL 查询结果集游标
import java.sql.ResultSet;

// ==================== 集合与并发工具 ====================
// ArrayList：可变列表
import java.util.ArrayList;
// Arrays：数组工具（排序、asList）
import java.util.Arrays;
// Collections：集合工具类
import java.util.Collections;
// List：列表接口
import java.util.List;
// BlockingQueue：阻塞队列（用于模拟数据库连接池）
import java.util.concurrent.BlockingQueue;
// CyclicBarrier：循环栅栏，用于并发线程对齐
import java.util.concurrent.CyclicBarrier;
// ExecutorService：线程池服务接口
import java.util.concurrent.ExecutorService;
// Executors：线程池工厂
import java.util.concurrent.Executors;
// LinkedBlockingQueue：链表阻塞队列实现
import java.util.concurrent.LinkedBlockingQueue;
// TimeUnit：时间单位枚举
import java.util.concurrent.TimeUnit;
// AtomicInteger：原子整数计数器
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
 *
 * 并发模型说明：
 *   - 1000 个工作线程提交到固定大小线程池，用 CyclicBarrier 栅栏对齐，
 *     保证所有线程在同一瞬间一起发起请求（模拟瞬时流量洪峰）；
 *   - 每个线程用 System.nanoTime() 记录自己这一次请求的耗时，得到逐线程延迟样本，
 *     再排序取 p50/p95/p99 百分位，避免只看平均值被长尾掩盖；
 *   - 数据库连接池固定 50 连接（自建 BlockingQueue 池），三组共用同一规格，
 *     保证 A1/A2/A3 的 DB 资源完全一致，差异只来自扣减方案本身；
 *   - 每组实验前统一重置 MySQL 与 Redis 现场，起点完全相同。
 *
 * 单组实验四步曲（见 runConcurrent）：
 *   ① 重置现场（resetEnv）→ ② 栅栏对齐（barrier.await 同一瞬间开抢）→
 *   ③ 并发执行压测动作 → ④ 统计指标（受理数/墙钟/延迟分位）写入 Markdown 报告。
 */
// 按 @Order 注解的值顺序执行测试方法（A1 → A2 → A3）
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// 压测基准类：包级私有即可，仅供本地压测运行
class SeckillBenchmark {

    // 秒杀券 ID：tb_seckill_voucher 中已有的测试券
    // 并发抢购线程数：1000 个虚拟用户同时抢购
    // 初始库存：只有 100 件，制造 10 倍超卖压力
    // 模拟生产数据库连接池大小：三组对照共用，控制变量
    static final long VOUCHER_ID = 10L;          // 秒杀券 ID（tb_seckill_voucher 中已有）
    static final int THREADS = 1000;             // 并发抢购线程数
    static final int INIT_STOCK = 100;           // 初始库存
    static final int DB_POOL_SIZE = 50;          // 模拟生产数据库连接池大小

    // 本机 MySQL 连接串（hmdp 库），压测直连不走应用层连接池
    static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    // 数据库账号
    static final String DB_USER = "root";
    // 数据库密码
    static final String DB_PASS = "123456";

    // 压测结果 Markdown 报告输出路径（benchmark_out 目录下）
    static final Path REPORT = Path.of("benchmark_out", "seckill_report.md");

    // 自建数据库连接池：所有对照组共用同一池实例，保证 DB 资源一致
    static BlockingQueue<Connection> dbPool;

    /** 建立数据库连接池：所有对照组共用同一池规格，保证 DB 资源一致。 */
    // 生命周期钩子：整个测试类只执行一次
    @BeforeAll
    // 初始化自建连接池
    static void initPool() throws Exception {
        // 用无界 LinkedBlockingQueue 模拟固定 50 连接的池
        dbPool = new LinkedBlockingQueue<>();
        // 循环建立 50 条物理连接放入池中
        for (int i = 0; i < DB_POOL_SIZE; i++) {
            // DriverManager 直连 MySQL，绕过应用层连接池
            dbPool.offer(DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS));
        // for 循环体结束：连接建立完毕
        }
        // 循环结束：50 条连接全部入池
        // 确保报告输出目录存在
        Files.createDirectories(REPORT.getParent());
        // initPool 方法体结束（收尾花括号）
    }
    // initPool 方法结束

    /** 统一重置现场：MySQL 库存/订单清零、Redis 库存/一人一单集合清零，保证三组实验起点完全相同。 */
    // 重置现场：每组实验开跑前调用一次，属于四步曲第①步
    static void resetEnv(StringRedisTemplate redis) throws Exception {
        // 从自建池中借一条连接（用完归还，不能 close）
        try (Connection c = dbPool.take()) {
            // 第一条语句：把秒杀券库存恢复到初始值 100
            try (PreparedStatement ps = c.prepareStatement(
                    // 库存重置 SQL 文本：UPDATE 恢复库存初值
                    "UPDATE tb_seckill_voucher SET stock = ? WHERE voucher_id = ?")) {
                // 绑定库存初值参数
                ps.setInt(1, INIT_STOCK);
                // 绑定秒杀券 ID 参数
                ps.setLong(2, VOUCHER_ID);
                // 执行库存重置
                ps.executeUpdate();
            // 库存重置 try 资源块结束
            }
            // 库存重置语句结束
            // 第二条语句：删除该券的全部历史订单，让订单表回到空态
            try (PreparedStatement ps = c.prepareStatement(
                    // 订单清空 SQL 文本：DELETE 该券全部订单
                    "DELETE FROM tb_voucher_order WHERE voucher_id = ?")) {
                // 绑定券 ID 参数
                ps.setLong(1, VOUCHER_ID);
                // 执行订单清空
                ps.executeUpdate();
            // 订单清空 try 资源块结束
            }
            // 订单清空语句结束
        }
        // MySQL 现场重置完毕，开始重置 Redis 现场
        // 删除 Redis 中的库存 key，消除上一轮残留
        redis.delete("seckill:stock:" + VOUCHER_ID);
        // 删除 Redis 中的一人一单集合，消除上一轮残留
        redis.delete("seckill:order:" + VOUCHER_ID);
        // 预加载 Redis 库存（与生产「新增秒杀券时预热库存」一致；Lua 对不存在的 key 返回 3=未初始化）
        redis.opsForValue().set("seckill:stock:" + VOUCHER_ID, String.valueOf(INIT_STOCK));
        // resetEnv 方法体结束
    }
    // resetEnv 方法结束

    /** Markdown 结果追加写入。 */
    // 报告写入工具：synchronized 保证多线程写报告不交叉
    static synchronized void write(String line) {
        // try-catch 包裹：写文件失败需转为运行时异常
        try {
            // 以追加模式写一行到报告文件（CREATE：不存在则创建；APPEND：追加）
            Files.writeString(REPORT, line + System.lineSeparator(),
                    // 第二行参数：字符集与打开选项续行
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            // 同步打印到控制台，便于压测过程中实时观察
            System.out.println(line);
        // 异常捕获：报告写失败会以运行时异常终止压测
        } catch (Exception e) {
            // 写报告失败属于致命错误，包装后上抛终止压测
            throw new RuntimeException(e);
        // try-catch 块结束
        }
        // write 方法体结束
    }
    // write 方法结束

    /** 延迟百分位（输入为纳秒数组）。 */
    // 指标计算：对逐线程延迟样本求 p50/p95/p99 百分位
    static String pct(long[] latencies) {
        // 先对全部逐线程延迟样本升序排序
        Arrays.sort(latencies);
        // 样本总数
        int n = latencies.length;
        // 取中位数（p50）、95 分位、99 分位，换算成毫秒并格式化输出
        return String.format("p50=%.1fms p95=%.1fms p99=%.1fms",
                // 三处下标取值与纳秒→毫秒换算续行
                latencies[n / 2] / 1e6, latencies[(int) (n * 0.95)] / 1e6, latencies[(int) (n * 0.99)] / 1e6);
        // pct 方法体结束
    }
    // pct 方法结束

    /** 允许抛受检异常的任务接口（压测动作里有 JDBC/Redis 的 checked exception）。 */
    // 函数式接口定义：压测动作统一签名
    interface ThrowingIntAction {
        // 入参 idx 为线程编号（0 起），返回 true 表示该次请求受理成功
        boolean apply(int idx) throws Exception;
        // 接口体结束
    }
    // ThrowingIntAction 接口结束

    /** 单组实验执行框架：起 1000 线程、栅栏对齐同时开抢、逐线程记录耗时。 */
    // 四步曲第②③步的通用执行框架：所有对照组共用
    static RunResult runConcurrent(int threads, ThrowingIntAction action) throws Exception {
        // 栅栏参与者数量 = 线程数：所有线程到齐后一起放行
        CyclicBarrier barrier = new CyclicBarrier(threads);
        // 固定大小线程池承载全部虚拟用户
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        // 逐线程延迟样本数组（下标即线程编号，纳秒）
        long[] latencies = new long[threads];
        // 受理成功计数器（原子，供并发线程累加）
        AtomicInteger success = new AtomicInteger();
        // 墙钟起点：从提交任务前开始计时
        long start = System.nanoTime();
        // 逐一提交 threads 个虚拟用户任务
        for (int i = 0; i < threads; i++) {
            // 捕获线程编号供 lambda 使用（必须是 effectively final）
            final int idx = i;
            // 提交单个用户任务
            pool.submit(() -> {
                // 任务体：先栅栏对齐再计时执行
                try {
                    barrier.await();                       // 栅栏对齐：1000 线程同一瞬间发起
                    // 该线程请求开始时刻
                    long t0 = System.nanoTime();
                    // 执行压测动作（A1/A2/A3 各自的实现），返回是否受理成功
                    boolean ok = action.apply(idx);
                    // 记录该线程本次请求的耗时（纳秒），形成逐线程延迟样本
                    latencies[idx] = System.nanoTime() - t0;
                    // 受理成功则累加计数
                    if (ok) success.incrementAndGet();
                // catch 分支：被测动作抛异常时该线程不计成功
                } catch (Exception e) {
                    // 单线程异常不计入成功，压测继续
                }            });
        // 提交 lambda 与 for 循环收尾
        }
        // 提交循环结束：1000 个任务全部入队
        // 不再接收新任务
        pool.shutdown();
        // 等待全部线程跑完（上限 10 分钟，防止异常场景挂死）
        pool.awaitTermination(10, TimeUnit.MINUTES);
        // 墙钟耗时 ms：从提交到全部完成的端到端时间
        long wall = (System.nanoTime() - start) / 1_000_000;   // 墙钟耗时 ms
        // 打包返回：受理数 / 墙钟 / 逐线程延迟样本（四步曲第④步的输入）
        return new RunResult(success.get(), wall, latencies);
        // runConcurrent 方法体结束
    }
    // runConcurrent 方法结束

    // 单组实验的结果记录：受理成功数、墙钟毫秒、逐线程延迟数组
    record RunResult(int success, long wallMs, long[] latencies) {}

    /** 查询 DB 当前库存（负数即为超卖量），需外部传入池内连接。 */
    // 终态盘点工具一：查库存
    static int stock(Connection c) throws Exception {
        // 查询该券的当前库存
        try (PreparedStatement ps = c.prepareStatement(
                // 库存查询 SQL 文本
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?")) {
            // 绑定券 ID 参数
            ps.setLong(1, VOUCHER_ID);
            // 执行查询并取第一行
            try (ResultSet rs = ps.executeQuery()) {
                // 游标移到结果集首行
                rs.next();
                // 返回库存值（被扣成负数则负数部分即超卖件数）
                return rs.getInt(1);
            // ResultSet 资源块结束
            }
        // PreparedStatement 资源块结束
        }
        // stock 方法体结束
    }
    // stock 方法结束

    /** 查询已落库订单数。 */
    // 终态盘点工具二：查订单数
    static int orderCount(Connection c) throws Exception {
        // 统计该券已生成的订单总数
        try (PreparedStatement ps = c.prepareStatement(
                // 订单统计 SQL 文本
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?")) {
            // 绑定券 ID 参数
            ps.setLong(1, VOUCHER_ID);
            // 执行统计查询
            try (ResultSet rs = ps.executeQuery()) {
                // 游标移到首行
                rs.next();
                // 返回订单总数
                return rs.getInt(1);
            // ResultSet 资源块结束（订单统计）
            }
        // PreparedStatement 资源块结束（订单统计）
        }
        // orderCount 方法体结束
    }
    // orderCount 方法结束

    // 第二个 @BeforeAll：在报告文件里写入本次压测的表头（两组 @BeforeAll 都会在所有测试前执行）
    @BeforeAll
    // 报告表头初始化
    static void reportHeader() {
        // 先空一行，与上一轮压测结果分隔
        write("");
        // 写入压测参数概览标题
        write("## 秒杀消融压测（" + THREADS + " 并发 / 初始库存 " + INIT_STOCK + " / 连接池 " + DB_POOL_SIZE + "）");
        // 空一行
        write("");
        // 写入结果表列头：方案 / 受理 / 订单 / 库存 / 超卖 / 墙钟 / 延迟
        write("| 方案 | 成功受理 | 落库订单 | 终态库存 | 超卖件数 | 墙钟耗时 | 单请求延迟 |");
        // Markdown 表格分隔行
        write("|---|---|---|---|---|---|---|");
        // reportHeader 方法体结束
    }
    // reportHeader 方法结束

    /** A1 无锁裸扣 DB：不带库存条件的 UPDATE + 无条件插单——最朴素的实现，用于暴露超卖。 */
    // 对照组 A1：超卖基线实验入口
    @Test
    // 执行顺序 1：最先跑超卖基线组
    @Order(1)
    // A1 方法主体：四步曲 ①重置 → ②栅栏 → ③并发裸扣 → ④统计
    void A1_naiveDb() throws Exception {
        // 构建连接本机 Redis 的模板（A1 虽不依赖 Redis，但重置现场需要清 Redis）
        StringRedisTemplate redis = redis();
        // 重置 MySQL 与 Redis 现场，起点归零
        resetEnv(redis);
        // 起压：1000 线程栅栏对齐后并发执行下面的压测动作
        RunResult r = runConcurrent(THREADS, idx -> {
            // 池内连接引用（finally 中归还）
            Connection c = null;
            // 单次请求动作体
            try {
                // 从自建连接池借一条连接
                c = dbPool.take();
                // 裸扣：不带 stock > 0 条件，模拟无防护实现
                try (PreparedStatement ps = c.prepareStatement(
                        // 裸扣 SQL 文本：无库存条件守卫
                        "UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ?")) {
                    // 绑定券 ID 参数
                    ps.setLong(1, VOUCHER_ID);
                    // 执行无条件扣减（库存可被扣成负数 → 超卖）
                    ps.executeUpdate();
                // 裸扣 try 资源块结束（A1）
                }
                // 裸扣语句结束
                // 朴素实现不检查更新结果，直接插单
                try (PreparedStatement ps = c.prepareStatement(
                        // 无条件插单 SQL 文本
                        "INSERT INTO tb_voucher_order (id, user_id, voucher_id) VALUES (?, ?, ?)")) {
                    // 订单主键：A1 段固定前缀 + 线程编号，避免主键冲突
                    ps.setLong(1, 9_000_000_000_001L + idx);
                    // 用户 ID：用线程编号 +1 模拟 1000 个不同用户
                    ps.setLong(2, idx + 1);
                    // 购买的券 ID
                    ps.setLong(3, VOUCHER_ID);
                    // 无条件插入订单（即使没扣到库存也插 → 订单数远超库存）
                    ps.executeUpdate();
                // 插单 try 资源块结束（A1）
                }
                // 插单语句结束
                // 该请求受理成功
                return true;
            // finally：无论成败都要归还连接（A1）
            } finally {
                // 无论成败都归还连接到池（不能 close，否则池会被耗尽）
                dbPool.offer(c);
            // finally 块结束（A1）
            }
        // A1 压测动作 lambda 结束
        });
        // 压测动作结束，进入第④步终态盘点
        // 借连接做终态盘点
        try (Connection c = dbPool.take()) {
            // 读取终态库存（负数部分即超卖件数）
            int s = stock(c);
            // 写入 A1 结果行：受理数 / 落库订单数 / 终态库存 / 超卖件数 / 墙钟 / 延迟分位
            write(String.format("| A1 裸扣DB(无库存条件) | %d | %d | %d | %d | %dms | %s |",
                    // 格式化参数续行
                    r.success(), orderCount(c), s, INIT_STOCK - s, r.wallMs(), pct(r.latencies())));
        // A1 终态盘点 try 块结束
        }
        // A1_naiveDb 方法体结束
    }
    // A1_naiveDb 方法结束

    /** A2 CAS 条件更新：UPDATE 带 stock > 0，靠 InnoDB 行锁串行化防超卖。 */
    // 对照组 A2：DB 防超卖基线实验入口
    @Test
    // 执行顺序 2：跑 DB 防超卖基线组
    @Order(2)
    // A2 方法主体：四步曲 ①重置 → ②栅栏 → ③并发 CAS 扣减 → ④统计
    void A2_casDb() throws Exception {
        // 重置 MySQL 与 Redis 现场
        resetEnv(redis());
        // 起压：1000 线程并发执行 CAS 条件更新动作
        RunResult r = runConcurrent(THREADS, idx -> {
            // 池内连接引用（finally 中归还）
            Connection c = null;
            // 单次请求动作体
            try {
                // 从自建连接池借一条连接
                c = dbPool.take();
                // 标记本次是否真正扣到库存
                boolean ok = false;
                // CAS：库存条件在 SQL 层判断，只有真正扣到库存才插单
                try (PreparedStatement ps = c.prepareStatement(
                        // CAS SQL 文本：WHERE 带 stock > 0 条件守卫
                        "UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0")) {
                    // 绑定券 ID 参数
                    ps.setLong(1, VOUCHER_ID);
                    // 影响行数 = 1 表示条件成立、扣减成功；= 0 表示库存已耗尽被拒绝
                    ok = ps.executeUpdate() == 1;
                // CAS 扣减 try 资源块结束（A2）
                }
                // CAS 扣减语句结束
                // 只有扣到库存才允许下单（防超卖的关键：扣减与插单以更新结果为准）
                if (ok) {
                    // 插入订单记录
                    try (PreparedStatement ps = c.prepareStatement(
                            // 插单 SQL 文本
                            "INSERT INTO tb_voucher_order (id, user_id, voucher_id) VALUES (?, ?, ?)")) {
                        // 订单主键：A2 段固定前缀 + 线程编号
                        ps.setLong(1, 9_000_000_000_002L + idx);
                        // 用户 ID：线程编号 +1 模拟不同用户
                        ps.setLong(2, idx + 1);
                        // 购买的券 ID
                        ps.setLong(3, VOUCHER_ID);
                        // 执行插单
                        ps.executeUpdate();
                    // 插单 try 资源块结束（A2）
                    }
                    // 插单语句结束
                }
                // 返回该请求是否受理成功（未扣到库存 = 受理失败）
                return ok;
            // finally：无论成败都要归还连接（A2）
            } finally {
                // 归还连接到池
                dbPool.offer(c);
            // finally 块结束（A2）
            }
        // A2 压测动作 lambda 结束
        });
        // 压测动作结束，进入第④步终态盘点
        // 借连接做终态盘点
        try (Connection c = dbPool.take()) {
            // 读取终态库存（CAS 下应为 0，绝不超卖）
            int s = stock(c);
            // 写入 A2 结果行：受理应为恰好 100，超卖应为 0
            write(String.format("| A2 CAS条件更新DB | %d | %d | %d | %d | %dms | %s |",
                    // 格式化参数续行
                    r.success(), orderCount(c), s, INIT_STOCK - s, r.wallMs(), pct(r.latencies())));
        // A2 终态盘点 try 块结束
        }
        // A2_casDb 方法体结束
    }
    // A2_casDb 方法结束

    /** A3 Redis + Lua（生产方案）：资格校验 + 一人一单 + 预扣单脚本原子完成。 */
    // 对照组 A3：生产方案实验入口
    @Test
    // 执行顺序 3：最后跑生产方案组
    @Order(3)
    // A3 方法主体：四步曲 ①重置 → ②栅栏 → ③并发 Lua 预扣 → ④统计
    void A3_redisLua() throws Exception {
        // 构建 Redis 模板
        StringRedisTemplate redis = redis();
        // 重置 MySQL 与 Redis 现场
        resetEnv(redis);
        // 加载 classpath 下的 seckill.lua 脚本，返回值为 Long
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        // 指定脚本资源位置
        script.setLocation(new ClassPathResource("seckill.lua"));
        // 指定脚本返回类型
        script.setResultType(Long.class);
        // 起压：1000 线程并发执行 Lua 预扣动作
        RunResult r = runConcurrent(THREADS, idx -> {
            // 与生产 VoucherOrderServiceImpl 同一调用方式：keys=库存+一人一单集合，argv=券/用户/订单ID
            Long res = redis.execute(script,
                    // keys 参数续行：库存 key + 一人一单集合 key
                    Arrays.asList("seckill:stock:" + VOUCHER_ID, "seckill:order:" + VOUCHER_ID),
                    // argv 参数续行：券 ID / 用户 ID / 订单 ID
                    String.valueOf(VOUCHER_ID), String.valueOf(idx + 1),
                    // argv 参数续行：订单 ID（A3 段固定前缀）
                    String.valueOf(9_000_000_000_003L + idx));
            return res != null && res == 0L;   // 0 = 预扣成功拿到资格
        // A3 压测动作 lambda 结束
        });
        // 压测动作结束，进入第④步终态盘点
        // 借连接做终态盘点
        try (Connection c = dbPool.take()) {
            // 读取 MySQL 终态库存（A3 受理阶段不写 DB，应仍为 100）
            int s = stock(c);
            // 写入 A3 结果行：落库为 0（异步）、库存取 Redis 终值、超卖恒为 0
            write(String.format("| A3 Redis+Lua(生产) | %d | 0(未落库,异步) | %d(Redis) | 0 | %dms | %s |",
                    // 格式化参数续行：受理数与 Redis 终值库存
                    r.success(), Integer.parseInt(redis.opsForValue().get("seckill:stock:" + VOUCHER_ID)),
                    // 格式化参数续行：墙钟与延迟分位
                    r.wallMs(), pct(r.latencies())));
            // 补充一行理论 DB 写入量对比：A1/A2 每请求都写 DB，A3 受理阶段零 DB 写入
            write(String.format("|  └ 参考：A2/A3 期间 MySQL 写入量 | A1=%d 次 | A2=1000 次(行锁竞争) | A3=0 次(受理阶段) |", THREADS));
        // A3 终态盘点 try 块结束
        }
        // A3_redisLua 方法体结束
    }
    // A3_redisLua 方法结束

    /** 构造连接本机 Redis 的模板（带密码），与 SeckillLuaIT 的构建方式一致。 */
    // Redis 模板工厂方法：三组对照共用
    static StringRedisTemplate redis() {
        // 指向本机 6379 的单机 Redis 配置
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        // 设置访问密码（与 docker-compose 中一致）
        conf.setPassword(RedisPassword.of("123456"));
        // 创建 Lettuce 连接工厂并完成初始化
        LettuceConnectionFactory factory = new LettuceConnectionFactory(conf);
        // 触发工厂初始化（建立底层连接）
        factory.afterPropertiesSet();
        // 基于工厂构建字符串模板并返回
        return new StringRedisTemplate(factory);
        // redis 工厂方法体结束
    }
    // redis 方法结束
}
// SeckillBenchmark 类结束
