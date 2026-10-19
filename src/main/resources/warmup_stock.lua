-- 仅为没有购买占用的券初始化/预热库存，不能用于恢复已有订单的活动。
-- KEYS[1] 库存键；KEYS[2] 终身一单占用集合。
-- ARGV[1] 读到的库存（空串表示键不存在）；ARGV[2] DB 库存。
-- 返回 1 写入成功，0 快照失效，-1 存在占用，-2 参数非法。
local target = tonumber(ARGV[2])
if not target or target < 0 or target ~= math.floor(target) then
    return -2
end
if redis.call('scard', KEYS[2]) > 0 then
    return -1
end
local current = redis.call('get', KEYS[1])
if (ARGV[1] == '' and current) or (ARGV[1] ~= '' and current ~= ARGV[1]) then
    return 0
end
redis.call('set', KEYS[1], ARGV[2])
return 1
