package com.hmdp;

import com.hmdp.cache.ShopBloomFilter;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static com.hmdp.utils.RedisConstants.SHOP_BLOOM_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证布隆覆盖不可靠时不会继续把真实店铺当作不存在。 */
class ShopBloomFilterTest {
    private ShopBloomFilter bloom;
    private RBloomFilter<Long> filter;
    private ShopMapper mapper;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        bloom = new ShopBloomFilter();
        RedissonClient redisson = mock(RedissonClient.class);
        filter = mock(RBloomFilter.class);
        mapper = mock(ShopMapper.class);
        when(redisson.<Long>getBloomFilter(SHOP_BLOOM_KEY)).thenReturn(filter);
        when(mapper.selectList(null)).thenReturn(Collections.singletonList(shop(1L)));
        ReflectionTestUtils.setField(bloom, "redissonClient", redisson);
        ReflectionTestUtils.setField(bloom, "shopMapper", mapper);
    }

    @Test
    void uninitializedFilterMustAllowLookup() {
        assertTrue(bloom.mightContain(42L));
        verifyZeroInteractions(filter);
    }

    @Test
    void failedAdditionMustAllowNewShopUntilSuccessfulReload() {
        bloom.init();
        when(filter.add(42L)).thenThrow(new IllegalStateException("Redis unavailable"));

        bloom.add(42L);

        // 即使 Redis 已恢复、contains 可以正常返回 false，也不能再相信不完整的过滤器。
        assertTrue(bloom.mightContain(42L));
        assertTrue(bloom.mightContain(99L));
        verify(filter, never()).contains(anyLong());

        doReturn(true).when(filter).add(42L);
        when(mapper.selectList(null)).thenReturn(Arrays.asList(shop(1L), shop(42L)));
        when(filter.contains(42L)).thenReturn(true);
        bloom.init();

        assertTrue(bloom.mightContain(42L));
        assertFalse(bloom.mightContain(99L));
        verify(filter, times(2)).add(42L);
    }

    @Test
    void lookupFailureMustKeepFailingOpenOnLaterRequests() {
        bloom.init();
        when(filter.contains(42L)).thenThrow(new IllegalStateException("Redis unavailable"));

        assertTrue(bloom.mightContain(42L));
        assertTrue(bloom.mightContain(99L));

        verify(filter, never()).contains(99L);
    }

    @Test
    void failedInitialLoadMustNotEnableFiltering() {
        when(mapper.selectList(null)).thenThrow(new IllegalStateException("Database unavailable"));

        bloom.init();

        assertTrue(bloom.mightContain(42L));
        verify(filter, never()).contains(anyLong());
    }

    private static Shop shop(long id) {
        Shop shop = new Shop();
        shop.setId(id);
        return shop;
    }
}
