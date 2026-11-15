// 包声明：学习性验证代码
package com.hmdp;

// Slf4j：Lombok 日志注解
import lombok.extern.slf4j.Slf4j;
// BeforeEach：每个用例前执行
import org.junit.jupiter.api.BeforeEach;
// Test：测试方法注解
import org.junit.jupiter.api.Test;
// RLock：Redisson 可重入分布式锁
import org.redisson.api.RLock;
// RedissonClient：Redisson 客户端
import org.redisson.api.RedissonClient;
// SpringBootTest：启动完整 Spring 上下文
import org.springframework.boot.test.context.SpringBootTest;

// Resource：注入 Spring Bean
import javax.annotation.Resource;
// TimeUnit：时间单位
import java.util.concurrent.TimeUnit;

// 开启日志
@Slf4j
// 启动 Spring 容器（真实连接 Redis）
@SpringBootTest
// 学习性验证类：观察 Redisson 分布式锁的 watchdog 与可重入机制
class RedissonTest {

// 注入 Redisson 客户端 Bean
    @Resource
// 客户端字段
    private RedissonClient redissonClient;

// 锁对象字段
    private RLock lock;

// 生命周期：每个用例前执行
    @BeforeEach
// setUp 方法体
    void setUp() {
// 获取名为 order 的可重入锁
        lock = redissonClient.getLock("order");
// setUp 方法结束
    }

// 验证用例：可重入 + watchdog 自动续期
    @Test
// method1 方法体（外层持锁调用 method2）
    void method1() throws InterruptedException {
        // 尝试获取锁
// tryLock：最多等 1 秒；不传 leaseTime 则启用 watchdog（默认 30 秒自动续期）
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
// 抢锁失败分支
        if (!isLock) {
// 记录失败日志
            log.error("获取锁失败 .... 1");
// 直接返回
            return;
// if 结束
        }
// try-finally：保证锁释放
        try {
// 记录成功日志
            log.info("获取锁成功 .... 1");
// 嵌套调用 method2：同一线程再次加锁（可重入验证）
            method2();
// 记录业务开始日志
            log.info("开始执行业务 ... 1");
// finally：释放锁
        } finally {
// 记录释放前日志
            log.warn("准备释放锁 .... 1");
// unlock：内部按重入计数递减，归零才真正删锁
            lock.unlock();
// finally 块结束
        }
// method1 方法结束
    }
// method2 方法体（内层再次获取同一把锁）
    void method2() {
        // 尝试获取锁
// 无参 tryLock：不传 leaseTime，同样走 watchdog 续期
        boolean isLock = lock.tryLock();
// 抢锁失败分支
        if (!isLock) {
// 记录失败日志
            log.error("获取锁失败 .... 2");
// 直接返回
            return;
// if 结束
        }
// try-finally：保证锁释放
        try {
// 记录成功日志（可重入成功，重入计数 +1）
            log.info("获取锁成功 .... 2");
// 记录业务开始日志
            log.info("开始执行业务 ... 2");
// finally：释放锁
        } finally {
// 记录释放前日志
            log.warn("准备释放锁 .... 2");
// unlock：重入计数 -1（外层锁仍保留）
            lock.unlock();
// finally 块结束
        }
// method2 方法结束
    }
// RedissonTest 类结束
}
