package com.hmdp.utils;

// Redis Key 与 TTL 常量池——集中定义各业务模块的 Redis key 前缀与过期时间。
// 命名规则统一为"业务:含义:"两段式前缀，实际 key 再拼接业务 ID。
// 统一管理便于排查 Redis 数据、调整过期策略。
public class RedisConstants {
    // 短信验证码 key：实际 key 为 login:code:{手机号}，value 是 6 位验证码字符串
    public static final String LOGIN_CODE_KEY = "login:code:";
    // 验证码有效期：2 分钟，超时未验证则失效，需重新获取
    public static final Long LOGIN_CODE_TTL = 2L;
    // 登录态 key：实际 key 为 login:token:{token}，value 是 UserDTO 的 Hash 字段
    // token 由 UUID 随机生成并返回给前端，前端后续请求携带于请求头
    public static final String LOGIN_USER_KEY = "login:token:";
    // 登录态有效期：36000 分钟（约 25 天），每次请求命中即续期（见 RefreshTokenInterceptor）
    public static final Long LOGIN_USER_TTL = 36000L;

    // 缓存空值 TTL：2 分钟。查询不存在的数据时缓存空串防穿透，
    // 短 TTL 保证 DB 新增该数据后能尽快生效
    public static final Long CACHE_NULL_TTL = 2L;

    // 店铺缓存 TTL：30 分钟。配合 CacheClient 的互斥锁方案防止缓存击穿
    public static final Long CACHE_SHOP_TTL = 30L;
    // 店铺缓存 key：实际 key 为 cache:shop:{店铺ID}，value 是 Shop 的 JSON 字符串
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    // 店铺缓存重建互斥锁 key：实际 key 为 lock:shop:{店铺ID}
    // 基于 SETNX 实现，保证同一时刻只有一个线程重建缓存
    public static final String LOCK_SHOP_KEY = "lock:shop:";
    // 互斥锁 TTL：10 秒。防止持锁线程宕机导致死锁，超时自动释放
    public static final Long LOCK_SHOP_TTL = 10L;

    // 秒杀 Redis 库存（String），实际 key 拼接券 ID；入口先预扣，MySQL 后台消费时再落库扣减。
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    // 每张券的已购买用户集合（Set），成员是 userId，用于 Lua 一人一单检查。
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    // 博客点赞集合 key：实际 key 为 blog:liked:{博客ID}，SortedSet 存点赞用户与时间戳，
    // 支持点赞排行（点赞人列表前 5 个）与点赞状态判断
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    // Feed 流收件箱 key：实际 key 为 feed:{粉丝ID}，SortedSet（score 为时间戳）实现滚动分页
    public static final String FEED_KEY = "feed:";
    // 商铺 GEO 集合 key：实际 key 为 shop:geo:{商户类型ID}，GEO 按经纬度存储店铺，
    // 支持"附近商铺"按距离排序查询
    public static final String SHOP_GEO_KEY = "shop:geo:";
    // 用户签到位图 key：实际 key 为 sign:{用户ID:年月}，Bitmap 按天置位，
    // 支持 BitCount 统计连续签到天数
    public static final String USER_SIGN_KEY = "sign:";
}
