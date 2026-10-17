package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    /**
     * 重建线程池必须有界：无界队列在热点失效时会堆任务，最终把堆内存吃光。
     * 队列满时由调用线程自己重建，形成天然背压。
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR;

    /**
     * 互斥锁最多等 2 秒，超时直接回源，避免请求线程被锁拖死
     */
    private static final long MUTEX_MAX_WAIT_MILLIS = 2_000L;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 16, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(500),
                runnable -> {
                    Thread thread = new Thread(runnable, "cache-rebuild");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        CACHE_REBUILD_EXECUTOR = executor;
    }

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
        // 逻辑过期不等于永不过期：再给一个物理 TTL 兜底，避免长期堆积
        long physicalTtl = Math.max(unit.toSeconds(time) * 3, 60L);
        stringRedisTemplate.expire(key, physicalTtl, TimeUnit.SECONDS);
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return parseCachedValue(key, json, type, time, unit);
        }
        // 空串是空值缓存标记，直接返回，防止穿透
        if (json != null) {
            return null;
        }

        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData;
        R r;
        try {
            JSONObject raw = JSONUtil.parseObj(json);
            if (!raw.containsKey("expireTime")) {
                // 普通缓存格式，直接按业务对象返回，避免把两种格式混用时误判为脏数据
                return JSONUtil.toBean(json, type);
            }
            redisData = JSONUtil.toBean(json, RedisData.class);
            Object data = redisData.getData();
            if (data == null) {
                // 脏数据：逻辑过期结构里没有业务数据，删掉按未命中处理
                stringRedisTemplate.delete(key);
                return null;
            }
            r = JSONUtil.toBean((JSONObject) data, type);
        } catch (Exception e) {
            log.warn("逻辑过期缓存解析失败，删除脏数据：key={}", key, e);
            stringRedisTemplate.delete(key);
            return null;
        }

        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime == null || expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 已逻辑过期：先返回旧值，抢到锁的线程异步重建
        String lockKey = LOCK_SHOP_KEY + id;
        String token = UUID.randomUUID().toString().replace("-", "");
        if (tryLock(lockKey, token)) {
            try {
                CACHE_REBUILD_EXECUTOR.execute(() -> {
                    try {
                        R newR = dbFallback.apply(id);
                        if (newR == null) {
                            // 数据被删除：不能把 null 写进逻辑过期结构，直接删 key
                            stringRedisTemplate.delete(key);
                        } else {
                            setWithLogicalExpire(key, newR, time, unit);
                        }
                    } catch (Exception e) {
                        log.error("逻辑过期缓存重建失败：key={}", key, e);
                    } finally {
                        unlock(lockKey, token);
                    }
                });
            } catch (Exception e) {
                // 线程池饱和且拒绝策略抛异常时，锁必须立即释放
                unlock(lockKey, token);
                log.warn("缓存重建任务提交失败：key={}", key, e);
            }
        }
        return r;
    }

    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        long deadline = System.currentTimeMillis() + MUTEX_MAX_WAIT_MILLIS;
        while (true) {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return parseCachedValue(key, json, type, time, unit);
            }
            if (json != null) {
                return null;
            }

            String lockKey = LOCK_SHOP_KEY + id;
            String token = UUID.randomUUID().toString().replace("-", "");
            if (tryLock(lockKey, token)) {
                try {
                    // 双重检查：等锁期间可能已被别的线程重建
                    json = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(json)) {
                        return parseCachedValue(key, json, type, time, unit);
                    }
                    if (json != null) {
                        return null;
                    }
                    R r = dbFallback.apply(id);
                    if (r == null) {
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }
                    this.set(key, r, time, unit);
                    return r;
                } finally {
                    unlock(lockKey, token);
                }
            }

            if (System.currentTimeMillis() >= deadline) {
                // 等锁超时：直接回源，宁可多打一次 DB 也不让请求挂死
                log.warn("互斥锁等待超时，回源查询：key={}", key);
                return dbFallback.apply(id);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待缓存重建锁被中断，回源查询：key={}", key);
                return dbFallback.apply(id);
            }
        }
    }

    /**
     * 兼容历史脏数据：逻辑过期结构与普通对象共用过同一个 key。
     * 读到带 expireTime/data 的外壳时解包业务对象，并顺便把 key 归一化成普通格式。
     */
    private <R> R parseCachedValue(String key, String json, Class<R> type, Long time, TimeUnit unit) {
        JSONObject raw;
        try {
            raw = JSONUtil.parseObj(json);
        } catch (Exception e) {
            // 数组或非法 JSON 无法按对象解析，交给调用方按原始格式兜底
            return JSONUtil.toBean(json, type);
        }
        if (!raw.containsKey("expireTime") || !raw.containsKey("data")) {
            return JSONUtil.toBean(json, type);
        }
        Object data = raw.get("data");
        if (data == null) {
            stringRedisTemplate.delete(key);
            return null;
        }
        R value = data instanceof JSONObject
                ? JSONUtil.toBean((JSONObject) data, type)
                : JSONUtil.toBean(json, type);
        this.set(key, value, time, unit);
        return value;
    }

    private boolean tryLock(String key, String token) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, token, 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 只删除自己持有的锁，避免锁过期后被其他线程续持时误删
     */
    private void unlock(String key, String token) {
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        } catch (Exception e) {
            log.warn("释放缓存重建锁失败：key={}", key, e);
        }
    }
}
