package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /**
     * 消费者落库。必须由消费者通过注入的代理调用，同类内部调用会让 @Transactional 失效
     */
    void createOrder(SeckillOrderMessage message);

    /**
     * 关闭未支付订单并回补库存，延迟关单消息与对账任务共用
     */
    boolean closeOrderIfUnpaid(Long orderId);

    Result queryOrderStatus(Long orderId);

    Result queryOrderDetail(Long orderId);

    Result queryMyOrders(Integer current, Integer status);

    Result payOrder(Long orderId, Integer payType);

    Result cancelOrder(Long orderId);

    Result useOrder(Long orderId);

    Result refundOrder(Long orderId);
}
