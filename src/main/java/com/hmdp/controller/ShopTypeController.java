package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author jarvis
 */
// ==================== 商铺类型业务域控制器（路由前缀 /shop-type） ====================
// 提供首页分类导航所需的商铺分类列表：按 sort 字段升序返回全部类型。
// 数据来源 tb_shop_type（MyBatis-Plus 条件构造器查询）。
// 类型数据量小且相对静态，当前直接查库；访问量大时可加 Redis 缓存。
// 该接口在登录白名单中，无需登录即可访问。
// 典型响应：[{"id":1,"name":"美食","sort":1,"icon":"..."}]
// 注意：返回的是实体列表，未做 DTO 裁剪，字段与表结构一一对应。
// 前端首页与商铺筛选页共用本接口，请求频率高，适合做缓存预热。
// 若后续新增分类管理接口（增删改），应仅限管理员操作。
// 类型 id 可用于 ShopController 的 /shop/of/type 接口按类型查商铺。
// 该列表同时被首页"分类导航"与发布笔记时的"选择商铺"筛选使用。
// 后续如需分页或搜索，可在此扩展带条件的查询接口。
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    /** 查询全部商铺类型列表：GET /shop-type/list。 */
    // 业务语义：为首页导航/分类筛选提供分类数据；
    // 一次返回全量（类型数量有限，不做分页），
    // 按 sort 升序保证展示顺序稳定。
    // @return ShopType 类型列表（含 id、name、sort、icon 等字段）
    // 无需登录：/shop-type/** 位于 MvcConfig 的 excludePathPatterns 白名单。
    // sort 字段由运营后台维护，数值越小越靠前。
    // 接口只读且幂等，可任意重试；返回顺序稳定，前端无需二次排序。
    // 响应结构：Result.ok(列表)，失败场景极少（数据库不可用时抛异常走全局兜底）。
    // 空表时返回空数组而非 null，前端可直接遍历。
    @GetMapping("list")
    public Result queryTypeList() {
        // orderByAsc("sort")：按分类权重升序，控制前端导航展示顺序
        // query() 为 MyBatis-Plus 条件构造器入口，此处无 where 条件
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        // list() 终结操作执行 SQL 并映射为实体列表
        // 包装为 Result 返回，data 字段承载列表
        return Result.ok(typeList);
    }
}
