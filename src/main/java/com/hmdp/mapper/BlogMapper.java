package com.hmdp.mapper;

import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 探店笔记表（tb_blog）的数据访问层 Mapper 接口。 */
/* 负责博客笔记的持久化读写：用户发布探店分享，shop_id 关联被点评的商铺， */
/* liked 字段记录被点赞的次数，是热门笔记榜与 Feed 流的数据来源。 */
/* 继承 MyBatis-Plus 的 BaseMapper<Blog> 后，单表 CRUD（insert、updateById、 */
/* selectById、selectPage 等）由框架按实体 Blog 的注解自动生成并注入， */
/* 无需手写 SQL 或 XML 映射文件；点赞数更新、分页查询等也都能复用这些 */
/* 内置方法，故接口体内无需声明自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface BlogMapper extends BaseMapper<Blog> {

}
