package com.hmdp.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.SHOP_CACHE_INVALIDATE_TOPIC;

/**
 * 店铺缓存失效广播。写库提交后发出，其他实例收到后清掉本地缓存。
 * 消息体只放店铺id，丢失时由本地 TTL 兜底。
 */
@Slf4j
@Component
public class ShopCacheInvalidationPublisher {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void publish(Long shopId) {
        if (shopId == null) {
            return;
        }
        try {
            stringRedisTemplate.convertAndSend(SHOP_CACHE_INVALIDATE_TOPIC, String.valueOf(shopId));
        } catch (Exception e) {
            // 广播失败不能让更新接口失败：本机缓存已失效，其他实例最迟在 TTL 后收敛
            log.warn("店铺缓存失效广播失败，shopId={}，reason={}", shopId, e.getMessage());
        }
    }
}
