package com.hmdp.mapper;

import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 商铺类型表（tb_shop_type）的数据访问层 Mapper 接口。 */
/* 负责商铺分类（美食、KTV、酒店等）的持久化读写，数据量小且极少变化， */
/* 业务上通常整表查出后以 Redis List 缓存，供首页类型导航栏渲染使用， */
/* 商铺表通过 type_id 与之关联。 */
/* 继承 MyBatis-Plus 的 BaseMapper<ShopType> 后，selectList 等基础查询由框架 */
/* 依据实体 ShopType 的注解自动注入，无需手写 SQL 或 XML 映射文件， */
/* 接口体内也无需声明任何自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface ShopTypeMapper extends BaseMapper<ShopType> {

}
