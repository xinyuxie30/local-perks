-- 滑动窗口限流：ZSET 存请求时间戳，窗口外的记录先清理再计数
-- KEYS[1] 限流键；ARGV[1] 窗口毫秒；ARGV[2] 上限；ARGV[3] 本次请求唯一标识
-- 返回：剩余可用次数（>=0），-1 表示被限流
-- 使用 Redis 服务器时间，避免多实例本机时钟漂移导致窗口错乱
local key = KEYS[1]
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local member = ARGV[3]

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
local count = redis.call('ZCARD', key)

if count < limit then
    redis.call('ZADD', key, now, member)
    -- 窗口过后整个 key 自然消失，多留 1 秒避免边界上的记录提前丢失
    redis.call('PEXPIRE', key, window + 1000)
    return limit - count - 1
end

redis.call('PEXPIRE', key, window + 1000)
return -1
