package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

// Redis 缓存数据的"包裹类"——配合 CacheClient 的"逻辑过期"方案使用。
// 逻辑过期方案：缓存不设置 Redis 原生 TTL（key 永不过期），而是把
// 业务数据和"逻辑过期时间"一起包装成该对象存入 Redis。查询时取出
// 后判断 expireTime 是否已过：未过期直接返回；已过期则获取互斥锁
// 异步重建缓存，同时先返回旧数据，保证服务不会因缓存重建而阻塞。
// 逻辑过期的高一致性方案特点：不会脏读（数据不删除只更新），且无死锁/击穿风险。
@Data
public class RedisData {
    /** 逻辑过期时间：与 Redis 原生 TTL 无关，仅用于业务判断缓存是否需要重建 */
    private LocalDateTime expireTime;
    /** 被包裹的实际业务数据（如 Shop 对象），存取时做 Object 与目标类型的转换 */
    private Object data;
}
