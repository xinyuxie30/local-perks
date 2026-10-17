package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @AdminOnly
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @AdminOnly
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        // 写入数据库
        return shopService.update(shop);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y,
            @RequestParam(value = "sortBy", required = false) String sortBy
    ) {
       return shopService.queryShopByType(typeId, current, x, y, sortBy);
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        if (current == null || current < 1 || current > SystemConstants.MAX_PAGE_NUMBER) {
            return Result.fail("页码不合法");
        }
        String keyword = StrUtil.isBlank(name) ? null : name.trim();
        if (keyword != null && keyword.length() > 50) {
            return Result.fail("搜索关键字过长");
        }
        if (keyword != null) {
            // 转义 LIKE 通配符，避免用户输入 % 或 _ 时把全表捞出来
            keyword = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        }
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(keyword != null, "name", keyword)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
