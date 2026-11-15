package com.hmdp.mapper;

import com.hmdp.entity.BlogComments;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 博客评论表（tb_blog_comments）的数据访问层 Mapper 接口。 */
/* 负责评论数据的持久化读写：每条评论隶属某条探店笔记（blog_id）， */
/* 由用户（user_id）发表，content 保存评论文本，parent_id 支持楼中楼回复。 */
/* 继承 MyBatis-Plus 的 BaseMapper<BlogComments> 后，框架依据实体类上的 */
/* @TableName、@TableId 注解在启动时自动注入单表 insert、deleteById、 */
/* updateById、selectById、selectList 等基础 CRUD 与分页能力， */
/* 因此无需手写 SQL，也不需要对应的 XML 映射文件， */
/* 接口体内也无需声明任何自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface BlogCommentsMapper extends BaseMapper<BlogComments> {

}
