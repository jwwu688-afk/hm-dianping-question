package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

// 全局唯一 ID 生成器——基于"时间戳 + 序列号"的拼位（位运算拼接）方案。
// 为什么不用数据库自增 ID：
// 1. 自增 ID 规律明显，容易被外部枚举出订单量等敏感信息；
// 2. 分库分表后多表自增会冲突，无法保证全局唯一；
// 3. 数据库自增性能受限于行锁，高并发下成为瓶颈。
// 本方案的 64 位 ID 结构：
// [符号位 1bit][时间戳 31bit][序列号 32bit]
// 时间戳 = 当前秒级时间戳 - 起始时间戳，可用约 69 年；
// 序列号由 Redis INCR 生成（按天分 key），同一秒内最多 2^32 个 ID。
// Redis INCR 的原子性保证集群下多实例生成的 ID 不重复。
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    // 起始时间：2022-01-01 00:00:00 的秒级时间戳，
    // 用相对时间戳可缩短位数，同时减少数字长度
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    // 序列号占 32 位：单日单业务最多可生成 2^32 个 ID，足够使用
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    // 构造注入 Redis 操作模板（该类由 Spring 管理，标注了 @Component）
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 生成下一个全局唯一 ID（订单、优惠券等业务共用，用 keyPrefix 区分）
    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        // 取当前时刻的秒级时间戳，再减去起始时间得到相对时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        // 按天拼 key（icr:业务:年:月:日）有两个好处：
        // 一是单 key 自增不会溢出；二是可按天统计业务量
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        // Redis INCR 原子自增，集群多实例并发下也能拿到不重复的序列号
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        // 位运算拼接：时间戳左移 32 位腾出低位，再按位或上序列号，
        // 得到 64 位全局唯一 ID（高位时间趋势递增，适合做索引）
        return timestamp << COUNT_BITS | count;
    }
}
