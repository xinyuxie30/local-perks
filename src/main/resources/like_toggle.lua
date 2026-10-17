-- 点赞切换：同一用户对同一目标只会有一条记录
-- KEYS[1] 点赞 ZSET；ARGV[1] 用户id；ARGV[2] 当前时间戳
-- 返回 1 表示本次点赞，0 表示本次取消点赞
local key = KEYS[1]
local userId = ARGV[1]
local score = ARGV[2]

if redis.call('ZSCORE', key, userId) then
    redis.call('ZREM', key, userId)
    return 0
end

redis.call('ZADD', key, score, userId)
return 1
