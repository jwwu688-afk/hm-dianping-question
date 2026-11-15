package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

// 基于 Redis 的简易分布式锁实现（SETNX + TTL + Lua 脚本释放）。
// 核心思路：
// 1. 加锁：SETNX（setIfAbsent）写入 线程标示，同时设置 TTL——原子操作，
//    保证"不存在才设置"且不会忘记设置过期时间，防死锁；
// 2. 释放：Lua 脚本先比对锁的持有者标示，一致才 DEL——保证原子性，
//    防止"判断不是自己的锁后被误删"（若判断与删除分两步，可能删掉
//    恰好超时后重新获得锁的其他线程的锁）。
// 典型使用场景：缓存重建互斥（防止缓存击穿时大量线程同时重建）。
// 与 Redisson 等成熟方案相比，本实现不支持可重入与看门狗自动续期，
// 适合教学演示 SETNX 锁的核心原理。
public class SimpleRedisLock implements ILock {

    // 锁的业务名称，用于区分不同业务的锁（如 shop 缓存重建锁）
    // 同一 name 的锁在 Redis 中是同一个 key，天然互斥
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // Redis 中锁的 key 前缀，实际 key 为 lock:{业务名}
    private static final String KEY_PREFIX = "lock:";
    // 线程标示前缀：JVM 启动时随机生成一个 UUID，拼接线程 ID 组成唯一标示。
    // 用 UUID 而非裸线程 ID 的原因：集群部署时不同机器的线程 ID 会重复，
    // 加 UUID 后标示全局唯一，避免误判"锁是自己持有的"。
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    // 释放锁的 Lua 脚本（unlock.lua），静态初始化只加载一次
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        // 类加载时从 classpath 加载脚本，避免每次释放锁都读文件
        // DefaultRedisScript 会把脚本通过 EVALSHA 发送，性能好
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        // 返回类型声明为 Long，对应 Lua 脚本的返回值（0 或 1）
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    // 尝试加锁（非阻塞）：抢不到立即返回 false，由调用方决定重试策略。
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示
        // 格式：{UUID}-{线程ID}，作为锁的 value，释放时用于校验持有者
        // 同一 JVM 内各线程的标示不同；不同 JVM 由 UUID 区分
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        // setIfAbsent 即 SET key value NX EX ttl：不存在才写入，带过期时间
        // 这一步由 Redis 保证原子性，多线程并发只有一个能成功
        // TTL 到期自动释放，即使持锁线程宕机也不会永久锁死
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 抢锁成功返回 true；success 为 null（Redis 异常等）也视为失败
        // Boolean.TRUE.equals 可安全处理 null，避免拆箱空指针
        return Boolean.TRUE.equals(success);
    }

    // 释放锁：执行 Lua 脚本，原子完成"校验持有者 + 删除"。
    // Lua 保证判断与删除不可分割，从根上防止误删他人锁。
    // 即使锁已超时易主，脚本比对不通过也不会执行删除。
    @Override
    public void unlock() {
        // 调用lua脚本
        // 脚本逻辑：GET key == 当前线程标示 ? DEL key : 0
        // KEYS[1] 传锁的 key，ARGV[1] 传当前线程标示
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
    /*@Override
    public void unlock() {
        // 获取线程标示
        // （被注释的旧实现）先 GET 再 DELETE 的两步释放：存在原子性缺陷——
        // 若 GET 判断通过后恰逢锁超时失效、且锁已被其他线程重新获取，
        // 随后的 DELETE 会误删别人的锁，因此改用上面的 Lua 方案。
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断标示是否一致
        if(threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
