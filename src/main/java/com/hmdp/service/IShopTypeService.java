package com.hmdp.service;

import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/** 商铺类型业务的服务接口，对应实体 ShopType（tb_shop_type 表）。 */
/* 继承 MyBatis-Plus 的 IService<ShopType> 后，首页类型导航的"查询所有类型" */
/* 通过 list() 完成数据回源，并整体缓存进 Redis（List 结构，key 为 */
/* cache:shop:type），命中则直接反序列化返回、未命中才查库， */
/* 减少对这张小表的高频访问，因此本接口无需声明任何自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface IShopTypeService extends IService<ShopType> {

}
