package com.hmdp.service.impl;

import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 商铺类型业务的服务实现类。 */
/* 本类为空壳实现：继承 MyBatis-Plus 的 ServiceImpl 后，框架在运行期 */
/* 已按泛型参数提供全部通用能力——第一个泛型 ShopTypeMapper 指向 */
/* 数据表 tb_shop_type（数据访问层），第二个泛型 ShopType 是 */
/* 与该表一一映射的实体类。查询全部类型（list）即可满足 */
/* 首页导航加 Redis List 缓存的需求，无需额外定制，故类体为空。 */
/* 若后续类型数据增长或需要多级分类，再在类体内扩展方法即可。 */
/* @author jarvis  @since 2026-09-16 */
/* @Service 注解将其注册为 Spring 容器中的单例 Bean，供控制器按接口注入。 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

}
