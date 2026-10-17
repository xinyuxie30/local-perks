package com.hmdp.controller;

import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitDimension;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 抢购秒杀券，成功返回订单id（模式 B 下订单可能仍在排队，需轮询状态）
     */
    @PostMapping("seckill/{id}")
    @RateLimit(prefix = "seckill", limit = 5, windowSeconds = 1,
            dimension = RateLimitDimension.USER, message = "手速太快了，缓一缓再试")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 我的订单分页，status 为空表示全部
     */
    @GetMapping("/list")
    public Result queryMyOrders(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "status", required = false) Integer status) {
        return voucherOrderService.queryMyOrders(current, status);
    }

    /**
     * 轮询抢购结果，订单落库前返回排队中
     */
    @GetMapping("/status/{orderId}")
    public Result queryOrderStatus(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.queryOrderStatus(orderId);
    }

    @GetMapping("/{orderId}")
    public Result queryOrderDetail(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.queryOrderDetail(orderId);
    }

    @PostMapping("/{orderId}/pay")
    public Result payOrder(@PathVariable("orderId") Long orderId,
                           @RequestParam(value = "payType", defaultValue = "1") Integer payType) {
        return voucherOrderService.payOrder(orderId, payType);
    }

    @PostMapping("/{orderId}/cancel")
    public Result cancelOrder(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.cancelOrder(orderId);
    }

    @PostMapping("/{orderId}/use")
    public Result useOrder(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.useOrder(orderId);
    }

    @PostMapping("/{orderId}/refund")
    public Result refundOrder(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.refundOrder(orderId);
    }
}
