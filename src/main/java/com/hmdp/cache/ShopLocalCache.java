package com.hmdp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.hmdp.config.LocalCacheProperties;
import com.hmdp.entity.Shop;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 店铺 L1 本地缓存。只缓存真实存在的店铺，空结果交给 Redis 空值缓存处理。
 */
@Component
public class ShopLocalCache {

    @Resource
    private LocalCacheProperties properties;

    private Cache<Long, Shop> cache;

    @PostConstruct
    public void init() {
        cache = Caffeine.newBuilder()
                .maximumSize(properties.getShopMaxSize())
                .expireAfterWrite(properties.getShopTtlSeconds(), TimeUnit.SECONDS)
                // 记录命中率，便于管理端观察多级缓存效果
                .recordStats()
                .build();
    }

    public Shop get(Long id) {
        return !properties.isShopLocalEnabled() || cache == null || id == null ? null : cache.getIfPresent(id);
    }

    public void put(Long id, Shop shop) {
        if (properties.isShopLocalEnabled() && cache != null && id != null && shop != null) {
            cache.put(id, shop);
        }
    }

    public void invalidate(Long id) {
        if (cache != null && id != null) {
            cache.invalidate(id);
        }
    }

    public void clear() {
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    public CacheStats stats() {
        return cache == null ? CacheStats.empty() : cache.stats();
    }

    public long estimatedSize() {
        return cache == null ? 0L : cache.estimatedSize();
    }
}
