-- 库存认领，模式 B 由消费者执行，把扣减从入口移到消费端
-- 返回：0 认领成功 / 1 库存不足 / 2 重复下单 / 3 券不存在或未预热
-- 1.参数列表
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 2.数据key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
local stock = redis.call('get', stockKey)
if not stock then
    return 3
end
if tonumber(stock) <= 0 then
    return 1
end
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end
-- 扣减与占用必须原子完成，否则并发下会出现扣了库存没占到资格
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0
