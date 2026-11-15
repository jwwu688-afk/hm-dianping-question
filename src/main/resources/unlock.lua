-- Redis 分布式锁「安全释放」Lua 脚本（客户端通过 EVAL/EVALSHA 原子执行）
-- KEYS[1]：锁的 key；ARGV[1]：加锁时写入的线程唯一标识（如 UUID）
-- 为什么要用 Lua 脚本而不是「GET 比较后再 DEL」两条命令：
--   Redis 执行 Lua 脚本期间不会插入其他命令，比较与删除天然构成原子操作；
--   若拆成两条命令，存在竞态：GET 判断「锁是我的」之后、DEL 之前，锁恰好过期，
--   其他线程成功加锁并写入了自己的标识，此时再 DEL 就会误删别人的锁，
--   导致多个线程同时持有锁，分布式互斥被破坏。
-- 因此必须把「比较 value 一致才删除」放在同一段 Lua 里执行，保证只释放自己持有的锁。
-- 比较线程标示与锁中的标示是否一致
if(redis.call('get', KEYS[1]) ==  ARGV[1]) then
    -- 释放锁 del key
    return redis.call('del', KEYS[1])
end
return 0