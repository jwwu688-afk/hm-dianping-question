package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/** 缓存工具类：封装缓存穿透/击穿三类防护方案，调用方只传 key 前缀、id、类型与查库函数。 */
@Slf4j
@Component
public class CacheClient {

    // Redis 客户端：本类所有缓存读写都走它
    private final StringRedisTemplate stringRedisTemplate;

    // 逻辑过期方案的重建线程池：独立于 Tomcat 工作线程，重建慢不拖累请求线程
    // 固定 10 线程：重建任务是短暂的查库+写缓存，10 个并发足够
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 构造注入：由 Spring 传入单例的 StringRedisTemplate
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // —— 三种防护方案的选型速查 ——
    // queryWithPassThrough：防穿透（空值缓存），实现最简单，适合一般热点数据；
    // queryWithMutex：防击穿（互斥锁），强一致、有等待，适合重建成本可控的数据；
    // queryWithLogicalExpire：防击穿（逻辑过期），无阻塞但要预热+容忍旧值，适合超热点。

    /** 写入普通缓存：对象序列化为 JSON 存入 String 结构，并设置 TTL。 */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        // 统一 JSON 序列化：读侧按类型反序列化即可，与存储介质解耦
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /** 写入「逻辑过期」缓存：不设 Redis TTL，把过期时间记进 RedisData 由读侧判断。 */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        // RedisData 是包装对象：data 装真实数据，expireTime 装逻辑过期时间点
        RedisData redisData = new RedisData();
        redisData.setData(value);
        // 过期时间 = 当前时间 + 传入时长；到点后由读侧判断是否触发重建
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        // 注意：不传 TTL —— key 物理上永不过期，这是逻辑过期方案的前提
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /** 缓存穿透防护版查询：空值缓存方案，三种缓存状态的区分见方法内注释。 */
    // —— 什么是缓存穿透 ——
    // 客户端请求「数据库里也不存在」的数据（如 id=-1），缓存永远不命中，
    // 每次都打到数据库；恶意伪造 id 会让数据库被持续消耗。
    // —— 空值缓存方案 ——
    // 查库为 null 时往缓存写一个空字符串并给短 TTL（CACHE_NULL_TTL，2 分钟）：
    // 后续同样 id 的请求在缓存层直接短路返回，不再访问数据库；
    // 短 TTL 保证数据库新增该数据后最多 2 分钟缓存自愈，不需要主动清缓存。
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        // 拼接完整缓存 key：前缀 + 业务 id，如 cache:shop:1
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        // 有正常 JSON（isNotBlank 同时排除 null 和空串）→ 缓存命中
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            // 反序列化为目标类型，调用方拿到的是实体而非字符串
            return JSONUtil.toBean(json, type);
        }

        // 判断命中的是否是空值
        // 查到的是一个空字符串 "",这是之前特意写入的“该数据不存在”的标记，直接返回 null,不再查数据库。
        // 缓存的三种状态：正常 JSON（上面返回）、空串（这里返回 null）、key 不存在（往下查库）
        if (json != null) {
            // 返回一个错误信息
            // 空串标记意味着数据库里也没有，直接短路，保护数据库
            return null;
        }

        // 4.不存在，根据id查询数据库
        // dbFallback 是调用方传入的查库函数，本类不关心具体查什么表
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        // 数据库也没有 → 写入空串标记（TTL 2 分钟），把后续穿透请求挡在缓存层
        if (r == null) {
            // 将空值写入redis
            // 空串 + 短 TTL：既挡住穿透，又不会长时间掩盖「数据后来有了」的事实
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        // 正常数据按业务 TTL 写入，之后同样 id 的请求就能命中缓存
        this.set(key, r, time, unit);
        // 返回查询结果
        return r;
    }

    /** 缓存击穿防护版查询：逻辑过期方案，「永不物理过期」的原理见方法内注释。 */
    // —— 什么是缓存击穿 ——
    // 热点 key 在物理过期的一瞬间，大量并发同时未命中、同时查库重建，数据库瞬间被打爆。
    // —— 逻辑过期方案为什么能防击穿 ——
    // 缓存写成「RedisData 包裹 + expireTime 字段」且【不设 Redis TTL】：
    // key 永不物理过期 → 缓存永远命中 → 不存在「集体未命中」的击穿窗口。
    // 过期与否由代码比对 expireTime 判断：未过期直接返回；已过期则抢互斥锁，
    // 抢到的线程把重建丢进独立线程池异步执行，当前线程【不等重建完成】直接返回旧数据；
    // tryLock 抢不到的线程也不等待，同样返回旧数据。
    // 即：重建期间所有请求都拿旧值，牺牲一小段新鲜度，换取零阻塞、零数据库压力。
    // —— 前提 ——
    // 要求缓存提前预热（先调 setWithLogicalExpire 写入），冷启动 key 不存在直接返回 null。
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 拼接完整缓存 key
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        // key 不存在说明未预热（逻辑过期方案不查库），直接返回 null
        if (StrUtil.isBlank(json)) {
            // 3.存在，直接返回
            // 注意这里的语义：返回 null 表示「缓存里没有」，而不是「数据不存在」
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        // 先解开 RedisData 包装，再取出真实的业务对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // data 字段被序列化成了 JSONObject，二次反序列化成目标类型
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        // 取出写入时记录的逻辑过期时间点
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        // 逻辑过期时间还在当前时间之后 → 数据仍然新鲜，直接返回
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        // 每个业务 id 一把锁，不同 id 的重建互不影响
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        // tryLock 只尝试一次、抢不到不等待：除一个线程外其余请求立刻返回旧值，无人阻塞
        if (isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            // 重建丢进独立线程池异步执行：当前请求不必等查库完成，握着锁立即返回旧数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    // 异步线程里完成查库 + 重写逻辑过期时间，与请求线程完全解耦
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    // 重建后 expireTime 推到未来，后续请求重新走「未过期」分支
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    // 无论重建成败都必须放锁，否则该 id 的后续重建全部失败
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        // 无论有没有抢到锁、重建是否开始，本请求都以旧值返回，响应耗时几乎为零
        // 这就是逻辑过期「永不物理过期、永不阻塞」的核心体现
        return r;
    }

    /** 缓存击穿防护版查询：互斥锁方案；SETNX + 递归重试的实现见方法内注释。 */
    // —— 与逻辑过期方案的对比 ——
    // 互斥锁：强一致（重建完成后才返回新数据），代价是未抢到锁的线程要等待；
    // 逻辑过期：零阻塞但返回旧值。本方案适合一致性要求高、重建耗时可控的数据。
    // —— 实现要点 ——
    // SETNX 互斥锁保证同一时刻只有一个线程查库重建，其余线程休眠后递归重试；
    // 重试会重新走一遍缓存查询，等第一个线程把缓存写好，它们就直接命中返回，
    // 数据库只承受一次查询压力。本方法也保留空值缓存判断，与穿透防护组合使用。
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 拼接完整缓存 key
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        // 正常 JSON → 缓存命中，绝大多数请求在这里直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, type);
        }
        // 判断命中的是否是空值
        // 命中空串穿透标记（shopJson 非 null 但为空），直接返回
        // 与穿透防护共用同一套空值约定
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.实现缓存重建
        // 走到这里说明缓存未命中且非穿透标记，需要查库重建
        // 4.1.获取互斥锁
        // 每个业务 id 一把锁：锁粒度到 id，不同数据互不影响
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.获取锁失败，休眠并重试
                // 等 50ms 再递归重试：重试会重新查缓存，若重建已完成则直接命中
                Thread.sleep(50);
                // 递归调用本身构成了「双检查」：只有持锁线程真正查库
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.4.获取锁成功，根据id查询数据库
            // 双检查体现为递归重试后重新走缓存查询，最终只有持锁线程真正查库
            r = dbFallback.apply(id);
            // 5.不存在，返回错误
            if (r == null) {
                // 将空值写入redis
                // 重建时发现数据不存在，同样写空串标记，穿透防护在锁内完成
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            // 重建结果写回缓存，等待中的线程随后即可命中
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            // sleep 被打断时恢复中断语义并向上抛
            throw new RuntimeException(e);
        }finally {
            // 7.释放锁
            // finally 保证任何路径（成功/失败/异常）都会放锁
            unlock(lockKey);
        }
        // 8.返回
        return r;
    }

    /** 尝试获取分布式锁：SET key 1 NX EX 10；NX 与 10s 过期的原因见方法内注释。 */
    // setIfAbsent 对应 Redis 的 SET NX：key 不存在才写入，写入成功即抢到锁。
    // 必须同时设置 10 秒过期：万一持锁线程崩溃没能执行 unlock，
    // 锁也能自动释放，避免其他线程永久等待（死锁）。
    private boolean tryLock(String key) {
        // EX 10s：锁最长持有 10 秒，作为兜底保护
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // setIfAbsent 返回 Boolean 包装类型，用 isTrue 规避 null
        return BooleanUtil.isTrue(flag);
    }

    /** 释放分布式锁：直接删除锁 key，让下一个等待者可以抢到。 */
    private void unlock(String key) {
        // DEL 即释放；生产环境可升级为「校验持有者再删」的 Lua 原子释放
        stringRedisTemplate.delete(key);
    }
}
// —— 本类小结 ——
// set/setWithLogicalExpire：两种写缓存方式，后者把过期时间放进数据本体；
// queryWithPassThrough：空值缓存防穿透，三种缓存状态（JSON/空串/不存在）分流处理；
// queryWithMutex：SETNX 互斥锁 + 50ms 自旋递归重试防击穿，锁带 10s 过期防死锁；
// queryWithLogicalExpire：RedisData 包裹 + 逻辑过期判断 + 独立线程池异步重建，
// tryLock 抢不到不阻塞 —— key 永不物理过期，因此永远命中、永远无击穿窗口。
