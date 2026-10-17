-- 取消/超时关单时回补秒杀库存
-- KEYS[1] 秒杀库存 key；ARGV[1] 总库存（0 或空表示不设上限）
-- 返回：>0 回补后的库存；0 表示 key 不存在或已达总库存，跳过
-- 重点：key 不存在时绝不新建，避免把未预热/已下线的券凭空补出库存
local key = KEYS[1]
if redis.call('exists', key) == 0 then
    return 0
end

local stock = tonumber(redis.call('get', key))
if not stock then
    return 0
end

local total = tonumber(ARGV[1])
if total and total > 0 and stock >= total then
    return 0
end

return redis.call('incr', key)
