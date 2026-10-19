package com.hmdp;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.AdminServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 不启动业务数据库/MQ，验证预热入口对丢键、历史订单和并发变化的处理。 */
class AdminWarmupTest {
    private AdminServiceImpl service;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private QueryChainWrapper<VoucherOrder> orders;
    private SeckillVoucher voucher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        service = spy(new AdminServiceImpl());
        redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        values = redis.opsForValue();
        IVoucherService vouchers = mock(IVoucherService.class);
        ISeckillVoucherService seckills = mock(ISeckillVoucherService.class);
        IVoucherOrderService orderService = mock(IVoucherOrderService.class);
        orders = mock(QueryChainWrapper.class, RETURNS_SELF);
        when(orderService.query()).thenReturn(orders);
        when(vouchers.getById(1L)).thenReturn(new Voucher());
        voucher = new SeckillVoucher();
        voucher.setVoucherId(1L);
        voucher.setStock(10);
        voucher.setTotalStock(10);
        when(seckills.getById(1L)).thenReturn(voucher);
        ReflectionTestUtils.setField(service, "voucherService", vouchers);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckills);
        ReflectionTestUtils.setField(service, "voucherOrderService", orderService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        doReturn(Result.ok(Collections.emptyMap())).when(service).seckillStats(1L);
    }

    @Test
    void missingStockWithHistoricalOrdersMustNotBeRecreated() {
        when(orders.count()).thenReturn(1);
        assertFalse(service.warmUpSeckill(1L, false).getSuccess());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void forceMustAlsoProtectCanceledOrderHistory() {
        when(values.get("seckill:stock:1")).thenReturn("5");
        when(orders.count()).thenReturn(1);
        assertFalse(service.warmUpSeckill(1L, true).getSuccess());
        // 不按订单状态过滤，已取消订单的终身购买资格也必须保留。
        verify(orders, never()).in(anyString(), anyCollection());
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void concurrentClaimMustCauseWarmupToFail() {
        when(values.get("seckill:stock:1")).thenReturn("5");
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(-1L);
        assertFalse(service.warmUpSeckill(1L, true).getSuccess());
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void freshVoucherUsesAtomicSnapshotAndOccupancyCheck() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(1L);
        assertTrue(service.warmUpSeckill(1L, false).getSuccess());
        verify(redis).execute(any(RedisScript.class),
                eq(Arrays.asList("seckill:stock:1", "seckill:order:1")), eq(""), eq("10"));
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void normalWarmupPreservesExistingStock() {
        when(values.get("seckill:stock:1")).thenReturn("5");
        assertTrue(service.warmUpSeckill(1L, false).getSuccess());
        verify(orders, never()).count();
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void invalidDatabaseStockIsRejectedBeforeRedisAccess() {
        voucher.setStock(null);
        assertFalse(service.warmUpSeckill(1L, true).getSuccess());
        verify(values, never()).get(anyString());
    }
}
