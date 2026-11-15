package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author jarvis
 */
// ==================== 探店笔记业务域控制器（路由前缀 /blog） ====================
// 职责：发布笔记并推送到粉丝 Feed 收件箱（推模式）、点赞与取消点赞
// （数据库计数 + Redis ZSet 时间戳分数）、热点笔记分页、笔记详情、
// Top5 点赞用户榜、按作者分页查询、关注人 Feed 流滚动分页
// （ZREVRANGEBYSCORE + offset 解决同一时间戳多条记录的重复/漏读）。
// 除 /blog/hot 在登录白名单（可匿名）外，其余接口均需登录；
// 业务逻辑均委托 IBlogService 实现，本类只做路由与参数承接。
// 返回值统一为 Result 结构；分页大小使用 SystemConstants.MAX_PAGE_SIZE。
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    /** 发布探店笔记：POST /blog，请求体为 Blog JSON。 */
    // 业务语义：为笔记补齐当前登录用户 id 后落库；
    // 查询作者的全部粉丝（tb_follow），把笔记 id 以发布时间戳为分数
    // 写入每个粉丝的 Feed 收件箱（Redis ZSet，key=feed:{粉丝id}），
    // 实现关注推送（推模式）；落库失败直接返回失败，不推送。
    // @param blog 笔记内容（标题、图片、文字）
    // @return 新增笔记 id，前端据此跳转详情页
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /** 点赞/取消点赞笔记：PUT /blog/like/{id}。 */
    // 业务语义：以 Redis ZSet（key=blog:liked:{id}）记录点赞用户，
    // 分数为点赞时间戳，保证榜单可按点赞先后排序；
    // 首次点赞：数据库 liked 计数 +1，更新成功才 zadd 写入 ZSet；
    // 重复请求视为取消点赞：liked -1，并从 ZSet 移除成员；
    // 两个存储按"先数据库后 Redis"顺序更新，保证 Redis 中记录有效。
    // @param id 笔记 id
    // @return 无业务数据
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    /** 分页查询当前登录用户自己发布的笔记：GET /blog/of/me?current=页码。 */
    // 业务语义：从 UserHolder 的 ThreadLocal 取当前用户 id，
    // 按 user_id 等值条件配合 MyBatis-Plus 分页查询；
    // 页大小取 SystemConstants.MAX_PAGE_SIZE，current 默认第 1 页。
    // @param current 页码
    // @return 当前页笔记列表
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /** 分页查询热点笔记：GET /blog/hot?current=页码。 */
    // 业务语义：按 liked 点赞数倒序分页，是首页热门榜单的数据源；
    // 每条笔记会填充作者昵称/头像与当前用户点赞状态（isLike），
    // 未登录时跳过点赞状态判断（service 内处理）；
    // 本接口在登录白名单（MvcConfig excludePathPatterns）中，可匿名访问。
    // @param current 页码，默认第 1 页
    // @return 热点笔记列表
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /** 查询笔记详情：GET /blog/{id}。 */
    // 业务语义：笔记不存在时返回失败"笔记不存在"；
    // 存在则附带作者昵称/头像（queryBlogUser），并查 Redis ZSet
    // 判断当前用户是否已点赞，填充 isLike 字段。
    // @param id 笔记 id
    // @return 笔记详情（含作者信息与点赞状态）
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /** 查询笔记点赞用户榜单（前 5 名）：GET /blog/likes/{id}。 */
    // 业务语义：ZRANGE 取点赞 ZSet 中分数（时间戳）最小的 5 个成员，
    // 即最早点赞的 Top5 用户；
    // 再用 WHERE id IN (...) ORDER BY FIELD(id, ...) 保持榜单顺序
    // 查回用户 DTO；无点赞记录时返回空列表。
    // @param id 笔记 id
    // @return Top5 点赞用户列表
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    /** 分页查询指定用户的笔记：GET /blog/of/user?current=页码&id=用户id。 */
    // 业务语义：与 queryMyBlog 的区别是用户 id 来自查询参数而非登录态，
    // 用于浏览他人主页的笔记列表；
    // 仅返回笔记列表，不附带点赞状态与作者信息。
    // @param current 页码，默认第 1 页
    // @param id 目标用户 id
    // @return 该用户的当前页笔记列表
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /** 查询关注人的笔记 Feed 流（滚动分页）：GET /blog/of/follow?lastId=&offset=。 */
    // 业务语义：ZREVRANGEBYSCORE 从当前用户收件箱（key=feed:{用户id}）
    // 取分数（时间戳）小于等于 lastId 的笔记，每次取 2 条；
    // 配合 offset 处理同一时间戳多条记录的重复/漏读，
    // 响应中会带回下次滚动所需的 minTime 与 offset；
    // 实现细节见 BlogServiceImpl.queryBlogOfFollow。
    // @param max 上次查询结果中的最小时间戳（首次传当前时间）
    // @param offset 与最小时间戳相同的条数偏移
    // @return Feed 流笔记列表
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }
}
