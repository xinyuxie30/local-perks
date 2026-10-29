-- Outbox 消费者按稳定事件 ID 回补；不会删除 seckill:order 的终身一单记录。
-- KEYS[1] 秒杀库存键；KEYS[2] 该事件专用的回补标记键。
-- ARGV[1] 正整数总库存（MySQL INT 范围）；ARGV[2] 稳定事件 ID。
-- 返回 1 已回补，2 已处理；-1 库存键缺失，-2 库存类型/值非法，
-- -3 已达/超过总库存，-4 参数非法，-5 标记类型/值异常。
-- 标记不自动过期；清理必须与 DB 事件归档、消息重放窗口协调。
-- 当前使用单机 Redis；迁移到 Cluster 前两个键必须使用相同 hash tag。
if #KEYS ~= 2 or #ARGV ~= 2 or not KEYS[1] or KEYS[1] == ''
        or not KEYS[2] or KEYS[2] == '' or KEYS[1] == KEYS[2]
        or not ARGV[2] or ARGV[2] == '' or #ARGV[2] > 128 then
    return -4
end

local function validInteger(value)
    if not value or not string.match(value, '^%d+$') or #value > 10 then
        return nil
    end
    local number = tonumber(value)
    if not number or number > 2147483647 then
        return nil
    end
    return number
end

local total = validInteger(ARGV[1])
if not total or total <= 0 then
    return -4
end

-- 检查重复先于检查库存。已处理事件不能因库存后来消失而重新回补。
local markerType = redis.call('type', KEYS[2]).ok
if markerType ~= 'none' then
    if markerType ~= 'string' or redis.call('get', KEYS[2]) ~= ARGV[2] then
        return -5
    end
    return 2
end

local stockType = redis.call('type', KEYS[1]).ok
if stockType == 'none' then
    return -1
end
if stockType ~= 'string' then
    return -2
end
local stock = validInteger(redis.call('get', KEYS[1]))
if not stock then
    return -2
end
if stock >= total then
    return -3
end
local ttl = redis.call('pttl', KEYS[1])
if ttl == -2 then
    return -1
end

-- Lua 不支持事务回滚。将库存和标记放在同一个 MSET 命令内写入，
-- 避免先 INCR 后 SET 时，第二条命令失败留下无标记的库存回补。
-- 前面的类型、范围、参数校验均不产生写入；MSET 失败不会只改其中一个键。
redis.call('mset', KEYS[1], string.format('%.0f', stock + 1), KEYS[2], ARGV[2])
-- MSET 会清除库存原有 TTL；恢复它，标记保持永久。即使这里出现运行时异常，
-- 库存和标记已一起写入，重试会返回 2，不会再次 +1（TTL 异常需排查）。
if ttl >= 0 then
    redis.call('pexpire', KEYS[1], math.max(ttl, 1))
end
return 1
