package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author jarvis
 */
// ==================== 商铺业务域控制器（路由前缀 /shop） ====================
// 核心能力：
// 1) 商铺详情查询走"缓存防护三段式"——默认按缓存穿透防护（空值缓存）
//    查询，可切换互斥锁或逻辑过期方案应对缓存击穿，策略由 cacheClient
//    封装在 ShopServiceImpl 中；
// 2) 更新商铺采用"先更新数据库、再删除缓存"策略保证缓存一致性；
// 3) 按类型 + Geo 坐标的距离排序分页（Redis GEOSEARCH）；
// 4) 按名称关键字模糊分页查询。
// 注意 /shop/** 在登录白名单中，浏览商铺不需要登录。
@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    /** 根据id查询商铺详情：GET /shop/{id}。 */
    // 业务语义：查询优先走 Redis 缓存——即"缓存防护三段式"：
    // 穿透防护（命中空值缓存直接返回不存在，避免打穿数据库），
    // 可选互斥锁/逻辑过期应对击穿（当前启用穿透防护方案），
    // 未命中回源 MySQL 并回填缓存（TTL 见 CACHE_SHOP_TTL）。
    // 缓存与数据库均无该 id 时返回"店铺不存在"。
    // @param id 商铺id
    // @return 商铺详情数据
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        // 缓存防护三段式由 cacheClient 封装，具体策略见 ShopServiceImpl.queryById
        return shopService.queryById(id);
    }

    /** 新增商铺信息：POST /shop，请求体为 Shop JSON。 */
    // 业务语义：直接落库 MySQL，新增后不主动预热缓存，
    // 首次查询时由缓存逻辑回填。
    // @param shop 商铺数据（无 id，主键自增）
    // @return 商铺id
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // 返回店铺id（自增主键由 MyBatis-Plus 回填到实体）
        return Result.ok(shop.getId());
    }

    /** 更新商铺信息：PUT /shop，请求体为 Shop JSON（必须携带 id）。 */
    // 业务语义：缓存一致性采用"先更新数据库、再删除缓存"策略，
    // 而非更新缓存——以低概率的不一致窗口换取实现简单与最终一致；
    // id 为空时 Service 直接返回失败。
    // @param shop 商铺数据
    // @return 无
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        // 先更新数据库、再删除 Redis 中的缓存 key（CACHE_SHOP_KEY+id），见 ShopServiceImpl.update
        // 写入数据库
        return shopService.update(shop);
    }

    /** 根据商铺类型分页查询商铺信息：GET /shop/of/type?typeId=&current=&x=&y=。 */
    // 业务语义：不带坐标时退化为数据库按 type_id 分页查询；
    // 带坐标时走 Redis GEO：GEOSEARCH 按 SHOP_GEO_KEY+typeId 检索
    // 5km 半径内的商铺并返回距离，按距离升序在内存中截取
    // from~end 段实现分页（GEO 不支持直接 offset 分页）。
    // @param typeId 商铺类型
    // @param current 页码
    // @param x 用户经度（可选），传入时启用 GEO 距离排序
    // @param y 用户纬度（可选），与 x 成对出现
    // @return 商铺列表（带坐标时附 distance）
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y
    ) {
       // 有坐标：GEOSEARCH 按 SHOP_GEO_KEY+typeId 检索并含距离，内存截取分页；
       // 无坐标：数据库按 type_id 分页
       return shopService.queryShopByType(typeId, current, x, y);
    }

    /** 根据商铺名称关键字分页查询：GET /shop/of/name?name=&current=。 */
    // 业务语义：name 为空时不过滤条件（返回全量分页），非空时按
    // like 模糊匹配；走 MyBatis-Plus 条件构造器分页，无缓存；
    // 页大小为 SystemConstants.MAX_PAGE_SIZE。
    // @param name 商铺名称关键字
    // @param current 页码
    // @return 商铺列表
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        // like 条件带 isNotBlank 判断：name 为空时不拼接模糊条件
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
