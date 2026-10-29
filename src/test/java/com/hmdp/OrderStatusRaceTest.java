package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.OrderStatusVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderStatusRaceTest {
    @AfterEach void clearUser() { UserHolder.removeUser(); }

    @Test void commitBetweenSqlReadAndQueueReadMustNotBecomeNotFound() {
        VoucherOrderServiceImpl service = spy(new VoucherOrderServiceImpl());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        UserDTO user = new UserDTO(); user.setId(12L); UserHolder.saveUser(user);
        VoucherOrder order = new VoucherOrder(); order.setId(42L); order.setUserId(12L); order.setStatus(1);
        doReturn(null, order).when(service).getById(42L);
        Result result = service.queryOrderStatus(42L);
        assertTrue(result.getSuccess());
        assertEquals("CREATED", ((OrderStatusVO) result.getData()).getState());
        user.setId(13L);
        doReturn(null, order).when(service).getById(42L);
        assertFalse(service.queryOrderStatus(42L).getSuccess());
    }
}
