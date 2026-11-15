package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/** 探店笔记（博客）业务的服务接口，对应实体 Blog（tb_blog 表）。 */
/* 涵盖笔记的浏览、点赞、发布与 Feed 流推送，是社区互动模块的核心。 */
/* 继承 MyBatis-Plus 的 IService<Blog> 获得通用 CRUD， */
/* 下列方法为具体业务语义。 */
/* @author jarvis  @since 2026-09-16 */
public interface IBlogService extends IService<Blog> {

    /** 分页查询热门探店笔记。 */
    /* 按点赞数从高到低分页返回，并附带作者昵称、头像（脱敏）， */
    /* 以及当前用户是否已点赞、是否已点赞到前 5 的标记，供前端高亮。 */
    Result queryHotBlog(Integer current);

    /** 按 id 查询笔记详情。 */
    /* 返回笔记内容并拼装作者信息（通过 UserService 批量查询用户表）， */
    /* 同时标记当前登录用户是否已给该笔记点赞。 */
    Result queryBlogById(Long id);

    /** 给笔记点赞或取消点赞（幂等切换）。 */
    /* 以 Redis Set（key = blog:liked:笔记id）记录点赞用户并写入时间戳 score： */
    /* 未点过赞则 Set 中添加当前用户并将 liked 字段加一；已点过则移除并减一。 */
    Result likeBlog(Long id);

    /** 查询给笔记点赞的用户列表（点赞人排行）。 */
    /* 基于上述 Set 的 ZSet 语义按点赞时间先后取前 5 名用户， */
    /* 返回昵称与头像，前端展示"xxx 等人觉得很赞"。 */
    Result queryBlogLikes(Long id);

    /** 发布探店笔记。 */
    /* 保存笔记后，向所有粉丝的 Feed 流收件箱（Redis ZSet，key = feed:用户id） */
    /* 推送该笔记 id（score 为时间戳），实现推模式的消息分发。 */
    Result saveBlog(Blog blog);

    /** 查询关注的人发布的笔记（Feed 流收件箱），滚动分页。 */
    /* 读取当前用户收件箱 ZSet 中 score 小于 max 的最后 offset 条消息， */
    /* 支持从上次读取位置继续往后翻（score 为发布时间戳）， */
    /* 并按 score 倒序拼装笔记与作者信息、返回本次最小 score 作为下次起点。 */
    Result queryBlogOfFollow(Long max, Integer offset);

}
