package com.hmdp;

import com.hmdp.cache.ShopLocalCache;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多级缓存：Redis 缓存被删后仍可由 L1 提供；更新后 L1/L2 同时失效
 */
@SpringBootTest
@ActiveProfiles("test")
class MultiLevelCacheTest {

    private static final Long SHOP_ID = 1L;
    private static final String SUFFIX = "-L1-test";

    @Resource
    private IShopService shopService;

    @Resource
    private ShopLocalCache shopLocalCache;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private String originalName;

    @AfterEach
    void restore() {
        if (originalName != null) {
            Shop shop = new Shop();
            shop.setId(SHOP_ID);
            shop.setName(originalName);
            shopService.update(shop);
        }
        shopLocalCache.invalidate(SHOP_ID);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + SHOP_ID);
    }

    @Test
    void localCacheShouldSurviveRedisEvictionAndInvalidateOnUpdate() {
        shopLocalCache.invalidate(SHOP_ID);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + SHOP_ID);

        Shop first = (Shop) shopService.queryById(SHOP_ID).getData();
        assertNotNull(first);
        assertEquals(first.getName(), shopLocalCache.get(SHOP_ID).getName());

        // 模拟 Redis 被淘汰：L1 仍应命中，不依赖 Redis
        stringRedisTemplate.delete(CACHE_SHOP_KEY + SHOP_ID);
        Shop second = (Shop) shopService.queryById(SHOP_ID).getData();
        assertEquals(first.getName(), second.getName());
        assertNull(stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + SHOP_ID),
                "L1 命中时不应回填 Redis");

        originalName = first.getName();
        String newName = originalName + SUFFIX;
        Shop update = new Shop();
        update.setId(SHOP_ID);
        update.setName(newName);
        Result updated = shopService.update(update);
        assertTrue(updated.getSuccess());

        assertNull(shopLocalCache.get(SHOP_ID), "更新后 L1 必须失效");
        Shop after = (Shop) shopService.queryById(SHOP_ID).getData();
        assertEquals(newName, after.getName(), "更新后应读到新值，不能返回旧缓存");
    }
}
