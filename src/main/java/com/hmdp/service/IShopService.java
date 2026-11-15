package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/** 商铺业务的服务接口，对应实体 Shop（tb_shop 表）。 */
/* 继承 MyBatis-Plus 的 IService<Shop> 获得通用 CRUD， */
/* 下列方法实现商铺详情缓存（防穿透）、缓存更新与分类型及附近商铺查询。 */
/* @author jarvis  @since 2026-09-16 */
public interface IShopService extends IService<Shop> {

    /** 按 id 查询商铺详情（带 Redis 缓存）。 */
    /* 缓存命中直接返回；未命中查库并回写缓存（key = cache:shop:id）， */
    /* 空值也缓存短时间以拦截恶意 id 造成的缓存穿透， */
    /* 并配合互斥锁或逻辑过期策略应对缓存击穿。 */
    Result queryById(Long id);

    /** 更新商铺信息。 */
    /* 采用"先更新数据库、再删除缓存"的策略保证一致性， */
    /* 避免先删缓存后写库期间并发读旧值回填的问题。 */
    Result update(Shop shop);

    /** 分类型查询商铺列表，支持按距离排序的"附近商铺"。 */
    /* x、y 均为空时按类型分页（MyBatis-Plus 分页查 tb_shop）； */
    /* 传入坐标则从 Redis GEO（key = shop:geo:类型id）按距离升序 */
    /* 分页返回带 distance 的商铺列表。 */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
