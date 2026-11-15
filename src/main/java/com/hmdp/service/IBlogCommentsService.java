package com.hmdp.service;

import com.hmdp.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;

/** 博客评论业务的服务接口，对应实体 BlogComments（tb_blog_comments 表）。 */
/* 继承 MyBatis-Plus 的 IService<BlogComments> 后，保存评论、按笔记 id 分页查 */
/* 评论、统计评论数等操作直接复用框架提供的 save、lambdaQuery、page、count */
/* 等通用方法，因此本接口无需声明任何自定义方法；评论人昵称、头像的脱敏 */
/* 展示由上层控制器借助 UserService 批量查用户完成。 */
/* @author jarvis  @since 2026-09-16 */
public interface IBlogCommentsService extends IService<BlogComments> {

}
