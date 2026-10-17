-- 秒杀资格校验与库存预扣，运行在模式 A 的「本地事务」中，不再发送消息
-- 返回：0 成功 / 1 库存不足 / 2 重复下单 / 3 券不存在或未预热
-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId
-- 2.3.事务标记key
local txKey = 'seckill:tx:' .. orderId

-- 3.脚本业务
-- 3.1.库存key不存在，说明该券未预热，直接拒绝而不是让 tonumber(nil) 报错
local stock = redis.call('get', stockKey)
if not stock then
    return 3
end
-- 3.2.判断库存是否充足
if tonumber(stock) <= 0 then
    return 1
end
-- 3.3.判断用户是否下单 SISMEMBER orderKey userId
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end
-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.占用购买资格 sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.6.写事务标记，Broker 回查时据此决定半消息提交还是回滚
redis.call('set', txKey, 'COMMIT', 'EX', 1800)
return 0
