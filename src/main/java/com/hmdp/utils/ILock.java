package com.hmdp.utils;

// 分布式锁的抽象接口——定义锁的统一行为契约。
// 项目中基于 Redis 实现（SimpleRedisLock），满足集群部署下
// 多个 JVM 实例之间的互斥需求（JDK 的 synchronized 只在单机 JVM 内有效）。
// 接口只暴露两个动作：尝试加锁与释放锁，语义简单、便于扩展。
// 未来可替换为 Redisson 等更完善的实现而不影响业务代码。
public interface ILock {

    // 尝试获取锁：非阻塞式（防阻塞特性），获取失败立即返回 false，
    // 不会像 synchronized 那样阻塞等待，调用方可自行决定重试或放弃。
    // 返回语义：true = 拿到锁；false = 没拿到，不应继续执行临界区代码。
    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间，过期后自动释放
     * @return true代表获取锁成功; false代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    // 释放锁：只允许持有者自己释放，防止误删其他线程/服务的锁。
    // 实现方必须保证"校验持有者 + 删除"的原子性（如用 Lua 脚本）。
    // 超时自动释放由实现方在加锁时设置 TTL 保证。
    /**
     * 释放锁
     */
    void unlock();
}
