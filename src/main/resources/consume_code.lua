-- 验证码一次性消费：比对成功才删除，避免同一个验证码被并发重放
-- KEYS[1] 验证码 key；ARGV[1] 用户提交的验证码
-- 返回 1 消费成功；0 验证码不匹配或已过期
local cached = redis.call('get', KEYS[1])
if cached and cached == ARGV[1] then
    redis.call('del', KEYS[1])
    return 1
end
return 0
