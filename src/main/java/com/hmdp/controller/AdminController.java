package com.hmdp.controller;

import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.Result;
import com.hmdp.service.IAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 管理端接口，仅白名单用户可访问。用于活动预热、库存观测与对账兜底。
 */
@AdminOnly
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Resource
    private IAdminService adminService;

    /**
     * 预热无订单、无占用的秒杀券。force=true 也不能绕过购买资格和库存快照检查；
     * 已有订单的活动需暂停后核对账本，不能通过预热恢复。
     */
    @PostMapping("/seckill/{voucherId}/warmup")
    public Result warmUpSeckill(@PathVariable("voucherId") Long voucherId,
                                @RequestParam(value = "force", defaultValue = "false") boolean force) {
        return adminService.warmUpSeckill(voucherId, force);
    }

    /**
     * 查看某张秒杀券的 DB/Redis 库存、占用人数与订单状态分布
     */
    @GetMapping("/seckill/{voucherId}/stats")
    public Result seckillStats(@PathVariable("voucherId") Long voucherId) {
        return adminService.seckillStats(voucherId);
    }

    /**
     * 手动触发一次对账（库存校正 + 超时补关单）
     */
    @PostMapping("/reconcile")
    public Result reconcile() {
        return adminService.reconcile();
    }

    /**
     * 本地缓存命中率统计
     */
    @GetMapping("/cache/stats")
    public Result cacheStats() {
        return adminService.cacheStats();
    }
}
