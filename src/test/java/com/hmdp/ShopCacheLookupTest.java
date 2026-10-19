package com.hmdp;

import com.hmdp.cache.ShopBloomFilter;
import com.hmdp.cache.ShopLocalCache;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 不连接数据库或 Redis，验证 L1 命中与后续缓存链路的边界。 */
class ShopCacheLookupTest {
    private ShopServiceImpl service;
    private ShopLocalCache localCache;
    private ShopBloomFilter bloomFilter;
    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        service = new ShopServiceImpl();
        localCache = mock(ShopLocalCache.class);
        bloomFilter = mock(ShopBloomFilter.class);
        cacheClient = mock(CacheClient.class);
        ReflectionTestUtils.setField(service, "shopLocalCache", localCache);
        ReflectionTestUtils.setField(service, "shopBloomFilter", bloomFilter);
        ReflectionTestUtils.setField(service, "cacheClient", cacheClient);
    }

    @Test
    void localHitMustNotContactEitherRedisBackedLayer() {
        Shop shop = new Shop();
        shop.setId(1L);
        when(localCache.get(1L)).thenReturn(shop);

        Result result = service.queryById(1L);

        assertTrue(result.getSuccess());
        assertSame(shop, result.getData());
        verifyZeroInteractions(bloomFilter, cacheClient);
    }

    @Test
    void invalidIdMustNotReachAnyCache() {
        assertFalse(service.queryById(null).getSuccess());
        assertFalse(service.queryById(0L).getSuccess());
        verifyZeroInteractions(localCache, bloomFilter, cacheClient);
    }

    @Test
    void reliableBloomNegativeStopsLookupAfterLocalMiss() {
        when(bloomFilter.mightContain(2L)).thenReturn(false);

        assertFalse(service.queryById(2L).getSuccess());

        verify(localCache).get(2L);
        verifyZeroInteractions(cacheClient);
        verify(localCache, never()).put(anyLong(), any(Shop.class));
    }

    @Test
    void remoteHitPopulatesLocalCache() {
        Shop shop = new Shop();
        shop.setId(2L);
        when(bloomFilter.mightContain(2L)).thenReturn(true);
        when(cacheClient.queryWithPassThrough(eq(CACHE_SHOP_KEY), eq(2L), eq(Shop.class),
                org.mockito.ArgumentMatchers.<Function<Long, Shop>>any(),
                eq(CACHE_SHOP_TTL), eq(TimeUnit.MINUTES))).thenReturn(shop);

        Result result = service.queryById(2L);

        assertTrue(result.getSuccess());
        assertSame(shop, result.getData());
        verify(localCache).put(2L, shop);
    }

    @Test
    void missingShopDoesNotPopulateLocalCache() {
        when(bloomFilter.mightContain(2L)).thenReturn(true);

        assertFalse(service.queryById(2L).getSuccess());

        verify(localCache, never()).put(anyLong(), any(Shop.class));
    }
}
