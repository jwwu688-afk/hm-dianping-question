package com.hmdp.controller;


import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author jarvis
 * @since 2026-09-16
 */
// ==================== 博客评论业务域控制器（路由前缀 /blog-comments） ====================
// 业务域：博客评论，对应数据表 tb_blog_comments，采用 MyBatis-Plus
// 前端控制器风格生成，与 Blog、Voucher 等控制器结构保持一致。
// 当前状态：预留入口，类体为空，暂未提供独立的评论接口。
// 评论内容的展示随 BlogController 的笔记详情接口（GET /blog/{id}）
// 一并返回；评论数量由 Blog 实体字段承载，无独立统计接口。
// 后续扩展方向：
// 1) POST /blog-comments：发布评论（需登录，校验笔记存在后再落库）；
// 2) GET /blog-comments/of/blog/{id}?current=页码：分页查询某笔记的评论；
// 3) 评论数变更时同步维护 Blog 实体上的评论计数字段。
// 权限约定：评论相关接口默认需要登录，登录态由 MvcConfig 注册的
// 拦截器链维护（RefreshTokenInterceptor 续期 + LoginInterceptor 校验），
// 当前登录用户经 UserHolder 从 ThreadLocal 获取。
// 返回值统一使用 Result 结构（success/errorMsg/data）。
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

}
