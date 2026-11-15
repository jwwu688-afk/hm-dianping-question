package com.hmdp.mapper;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 商铺表（tb_shop）的数据访问层 Mapper 接口。 */
/* 负责商铺信息的持久化读写：商铺归属某类型（type_id 关联 tb_shop_type）， */
/* 含名称、地址、营业时间、评分、经纬度（x/y，配合 Redis GEO 做附近商铺）等 */
/* 字段，是首页商铺列表、商铺详情缓存、附近商铺等功能的底层数据源。 */
/* 继承 MyBatis-Plus 的 BaseMapper<Shop> 后，按 id 查详情、按类型分页、 */
/* 更新商铺等操作由框架注入的 selectById、selectPage、updateById 等基础方法 */
/* 配合 Wrapper 条件构造器即可完成，因此无需手写 SQL 或 XML 映射文件， */
/* 接口体内无需声明任何自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface ShopMapper extends BaseMapper<Shop> {

}
