package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.vo.OrderVO;
import org.apache.ibatis.annotations.Param;

public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    IPage<OrderVO> queryMyOrders(IPage<OrderVO> page, @Param("userId") Long userId, @Param("status") Integer status);

    OrderVO queryOrderDetail(@Param("orderId") Long orderId, @Param("userId") Long userId);

    /**
     * 该券下产生过订单的去重用户数，对账时用于和 Redis 占用集合比对
     */
    Integer countDistinctUser(@Param("voucherId") Long voucherId);
}
