-- 秒杀入口：在一次 Redis 脚本执行中校验库存、一人一单并预扣库存。
-- KEYS[1]：seckill:stock:{voucherId}，String 库存。
-- KEYS[2]：seckill:order:{voucherId}，Set 已购买用户 ID。
-- ARGV[1..3]：券 ID、用户 ID、订单 ID。
-- 返回：0 成功；1 无库存；2 重复购买；3 库存未初始化；4 非法参数或 key 类型。
-- Lua 执行不被其他命令穿插，但运行时错误不会回滚此前写入，必须尽可能在任何写入前检查。
-- 本脚本只检查库存和重复购买，不检查秒杀开始/结束时间，也不直接发送 RabbitMQ。
-- 1. 检查所有目标 key 的 Redis 类型；不存在的 key 可由后续写命令创建，已有错类型直接失败。
local expected = {'string', 'set'}
for i = 1, 2 do
    local kind = redis.call('type', KEYS[i]).ok
    if kind ~= 'none' and kind ~= expected[i] then return 4 end
end
-- 2. ID 必须保持正整数字符串，不转为 Lua number，避免大订单 ID 精度损失。
for i = 1, 3 do
    if not string.match(ARGV[i], '^[1-9]%d*$') then return 4 end
end
-- 3. 预扣前确认库存已初始化且是合法非负整数，再检查剩余量。
local stock = redis.call('get', KEYS[1])
if not stock then return 3 end
if not string.match(stock, '^%d+$') or not tonumber(stock) then return 4 end
if tonumber(stock) <= 0 then return 1 end
-- 4. 同一用户同券不可重复受理。
if redis.call('sismember', KEYS[2], ARGV[2]) == 1 then return 2 end
-- 5. 校验完成后扣一份库存并记录已购买用户。
redis.call('decr', KEYS[1])
redis.call('sadd', KEYS[2], ARGV[2])
-- 6. 返回成功；Java 收到 0 后直接把订单消息发送到 RabbitMQ。
return 0

-- ==================== 原 Redis Stream Lua（已停用，仅供对照） ====================
-- -- 1.参数列表
-- -- 1.1.优惠券id
-- local voucherId = ARGV[1]
-- -- 1.2.用户id
-- local userId = ARGV[2]
-- -- 1.3.订单id
-- local orderId = ARGV[3]
--
-- -- 2.数据key
-- -- 2.1.库存key
-- local stockKey = 'seckill:stock:' .. voucherId
-- -- 2.2.订单key
-- local orderKey = 'seckill:order:' .. voucherId
--
-- -- 3.脚本业务
-- -- 3.1.判断库存是否充足 get stockKey
-- if(tonumber(redis.call('get', stockKey)) <= 0) then
--     -- 3.2.库存不足，返回1
--     return 1
-- end
-- -- 3.2.判断用户是否下单 SISMEMBER orderKey userId
-- if(redis.call('sismember', orderKey, userId) == 1) then
--     -- 3.3.存在，说明是重复下单，返回2
--     return 2
-- end
-- -- 3.4.扣库存 incrby stockKey -1
-- redis.call('incrby', stockKey, -1)
-- -- 3.5.下单（保存用户）sadd orderKey userId
-- redis.call('sadd', orderKey, userId)
-- -- 3.6.发送消息到队列中， XADD stream.orders * k1 v1 k2 v2 ...
-- redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
-- return 0
