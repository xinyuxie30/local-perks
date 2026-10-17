package com.hmdp.controller;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 前端启动时需要的公开配置。
 * <p>
 * 订单倒计时、分页大小这类参数如果在前端写死，后端调配置就会两边对不上，
 * 所以统一从这里下发；该路径在登录拦截器里放行。
 */
@Slf4j
@RestController
@RequestMapping("/config")
public class PublicConfigController {

    @Value("${trade.close-delay-minutes:30}")
    private int orderHoldMinutes;

    @Value("${trade.reconcile-batch-size:100}")
    private int reconcileBatchSize;

    @GetMapping("/public")
    public Result publicConfig() {
        Map<String, Object> config = new HashMap<>(4);
        config.put("orderHoldMinutes", Math.max(orderHoldMinutes, 1));
        config.put("reconcileBatchSize", reconcileBatchSize);
        config.put("serverTime", System.currentTimeMillis());
        return Result.ok(config);
    }
}
