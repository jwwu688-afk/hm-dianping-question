package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * <p>探店笔记服务：浏览/点赞/排行榜/发布与 Feed 推模式，设计说明见方法内注释。</p>
 *
 * @author jarvis
 * @since 2026-09-16
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    // 用户服务：补齐笔记作者信息时回查用户表
    @Resource
    private IUserService userService;

    // Redis 客户端：点赞 ZSet、Feed 收件箱都存这里
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 关注服务：发布笔记时用它查作者的全部粉丝
    @Resource
    private IFollowService followService;

    // —— 数据设计：点赞 ZSet（key = blog:liked:笔记id） ——
    // member：点赞用户的 id 字符串；score：点赞时的毫秒时间戳。
    // 一份数据两用：ZSCORE O(1) 判断「是否点过」，ZRANGE 按时间排序出「点赞排行榜」；
    // 相比建一张点赞表，省去了「判断是否点过」和「按时间排序」两套查询的实现成本。

    // —— 数据设计：Feed 收件箱（key = feed:粉丝id） ——
    // 发布笔记时把笔记 id 写进每个粉丝的收件箱 ZSet，score 为发布时间戳；
    // 粉丝刷「关注」页时只查自己的收件箱，配合 minTime/offset 做滚动分页。

    /** 分页查询热点笔记（按点赞数倒序）；补数据的原因见方法内注释。 */
    // 为什么不缓存列表：笔记列表更新频繁（点赞数实时变）且分页维度多（页码/排序），
    // 缓存命中率低、失效成本高，直接查库是更合理的取舍。
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        // “热点”的定义就是点赞数：按 liked 字段倒序排，取每页上限条数
        // MAX_PAGE_SIZE 是系统约定的列表页最大条数，防止一次拉取过多数据
        Page<Blog> page = query()
                // 点赞数倒序：热门笔记排前面
                .orderByDesc("liked")
                // current 页码由前端传入，从 1 开始
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        // Blog 表只存作者 userId，前端卡片需要昵称和头像，所以每条都要回查用户；
        // 未登录用户也能刷列表，所以补状态的两个方法内部都对「未登录」做了兜底
        // 逐条补齐：作者昵称/头像给前端展示，isLike 让前端知道点赞按钮该不该亮
        records.forEach(blog -> {
            // 补作者昵称与头像
            this.queryBlogUser(blog);
            // 补当前用户的点赞状态标记
            this.isBlogLiked(blog);
        });
        // 不用 Redis 缓存列表的原因：列表更新频繁且分页维度多，缓存收益低
        return Result.ok(records);
    }

    /** 查询单条笔记详情，补数据逻辑与列表页一致（作者信息 + 点赞状态）。 */
    // 详情页与列表页的数据形状必须完全一致，前端才能用同一套渲染逻辑。
    // 未登录访问详情也合法：isBlogLiked 内部会兜底。
    // id 不存在时快速失败，避免后续空指针。
    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        // 不存在的 id 直接失败返回，后续补数据无意义
        // fail 与 ok 的返回形状一致，前端统一按 Result 处理
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户
        queryBlogUser(blog);
        // 3.查询blog是否被点赞
        // 与列表页共用同一套补数据逻辑，保证列表和详情的 isLike 表现一致
        isBlogLiked(blog);
        // 补数据完成后返回完整实体
        // 与列表页字段对齐：name/icon/isLike 三件套都已就位
        return Result.ok(blog);
    }

    /** 判断当前登录用户是否点过赞，结果写入 blog.isLike；ZSet 设计见方法内注释。 */
    // 设计为 private 并被列表/详情/Feed 三处复用，保证所有入口的 isLike 口径一致。
    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            // 浏览场景允许未登录，isLike 保持默认 false 即可
            return;
        }
        // 拿到用户 id，后续判断都以它为 ZSet 的 member
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        // key 与 likeBlog 写入时的约定一致：blog:liked:笔记id
        // 为什么不查数据库：点赞是高频读操作，ZSCORE 一次内存判断远快于 count(*)
        String key = "blog:liked:" + blog.getId();
        // 点赞记录存于 Redis ZSet：member=用户id，score=点赞时间戳；
        // ZSCORE 命中即「点过」，O(1) 完成判断；同一份数据按 score 排序就是点赞排行榜，
        // 避免再建点赞表做两套存储
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // ZSet 里能查到该用户（score 不为 null）即已点赞
        blog.setIsLike(score != null);
    }

    /** 点赞 / 取消点赞（开关式幂等切换）；一致性设计见方法内注释。 */
    // —— 一致性说明：DB 与 Redis 双写没有包事务，靠「先库后缓存」的顺序兜底 ——
    // 数据库是权威数据：liked 计数必须准；Redis 是辅助数据：只影响排序与展示。
    // 先改库再改 Redis，Redis 失败只丢排序数据；若先写 Redis，改库失败会造成两边不一致。
    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        // 点赞人就是当前登录用户，拦截器保证此处一定已登录
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        // 以 ZSet 中是否已有该用户作为「是否点过」的唯一判据，数据库 liked 只负责计数
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // score 为 null 说明 ZSet 里没有该用户，即未点过赞
        if (score == null) {
            // 3.如果未点赞，可以点赞
            // 3.1.数据库点赞数 + 1
            // 用 SQL 自增（liked = liked + 1）而不是读出再加写回，避免并发下计数错乱
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2.保存用户到Redis的set集合  zadd key value score
            // score 存点赞时间戳，既做「点没点过」判据，也是排行榜的排序依据；
            // 执行顺序必须是先改库、成功后再写 ZSet：即便 Redis 写失败，损失的只是一条
            // 点赞时间数据，计数依然准确；反过来一旦改库失败就会出现「ZSet 有点赞但计数没变」
            if (isSuccess) {
                // zadd 写入 member=用户id、score=当前时间戳
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.如果已点赞，取消点赞
            // 4.1.数据库点赞数 -1
            // 与加分分支同理，SQL 直接自减
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2.把用户从Redis的set集合移除
            // 同样保证先改库成功才动 Redis，两步的失败方向保持一致
            if (isSuccess) {
                // ZREM 移除该用户，排行榜与点赞判断随之失效
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        // 点赞与取消共用一个接口，由 ZSet 是否命中决定走哪个分支
        // 两步操作没有包事务：Redis 侧失败只丢排序数据，数据库计数始终是权威值
        return Result.ok();
    }

    /** 查询笔记的点赞排行榜（最早点赞的前 5 人）；顺序保持见方法内注释。 */
    // 「最早点赞」的产品语义：最早给笔记点赞的用户最值得关注，所以 ZSet 升序取前 5。
    // 拿到 id 后必须回 MySQL 查昵称头像，而 IN 查询会打乱顺序，因此要 ORDER BY FIELD 兜回。
    @Override
    public Result queryBlogLikes(Long id) {
        // 与点赞写入相同的 key，读的正是 likeBlog 维护的 ZSet
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        // score 是点赞时间戳，按分数升序取前 5 条 = 最早点赞的 5 个用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        // 还没人点赞时直接返回空列表，避免后续 IN 查询空 id 集合报错
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 拼接逗号串供 ORDER BY FIELD 使用
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        // MySQL 的 IN 查询不保证返回顺序，必须追加 ORDER BY FIELD(id, ...)
        // 让结果保持排行榜顺序，否则前端展示顺序会乱
        List<UserDTO> userDTOS = userService.query()
                // id 集合来自 ZSet member，顺序即点赞先后
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                // 实体转 DTO：只暴露昵称头像等展示字段
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                // 收集为列表返回
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }

    /** 发布探店笔记并把笔记 id 推送到所有粉丝的收件箱（推模式）；设计取舍见方法内注释。 */
    // —— 推模式 vs 拉模式 ——
    // 推模式（写扩散）：发布时写 N 个粉丝的收件箱，读时只查自己的收件箱，读路径快；
    // 拉模式（读扩散）：发布只写自己的发件箱，读时去每个关注对象那里拉取合并，写快读慢。
    // 本项目粉丝量中等，选推模式；百万粉丝的大 V 场景应改拉模式或推拉结合。
    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        // 作者就是当前登录用户，防止伪造 userId
        UserDTO user = UserHolder.getUser();
        // 把作者 id 写进笔记实体，落库时一并保存
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean isSuccess = save(blog);
        // 入库失败就终止：笔记 id 都没有，谈不上推送
        if(!isSuccess){
            return Result.fail("新增笔记失败!");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        // follow_user_id = 作者 表示“关注了作者的人”，即作者的全部粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        // 推模式（写扩散）：发布时立刻写所有粉丝的收件箱，读路径只查自己的收件箱，
        // 简单且快；代价是发布要写 N 份（粉丝越多发布越慢），大 V 场景一般改拉模式
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            // follow 表里 userId 是粉丝、followUserId 是被关注者
            Long userId = follow.getUserId();
            // 4.2.推送
            // 收件箱按粉丝一人一份；score 用发布时间戳，是后续滚动分页的游标
            String key = FEED_KEY + userId;
            // 推送在入库成功之后进行，保证收件箱里的 id 一定能在库里查到
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        // 前端拿到 id 后可跳转到笔记详情页
        return Result.ok(blog.getId());
    }

    /** 查询当前用户的 Feed 收件箱（滚动分页）；offset 公式的推导见方法内注释。 */
    // —— 滚动分页原理（重要） ——
    // 收件箱 ZSet 的 score 是发布时间戳，同一秒发布的笔记分数相同，所以必须处理「同分」。
    // 请求参数 (max, offset)：max 是上一页的最小时间戳，offset 是上一页里与 max 同分的条数。
    // ZREVRANGEBYSCORE(key, 0, max, offset, 2)：从 max 开始倒序、跳过 offset 条同分记录、取 2 条。
    // 返回值里再算出本页的 minTime 与同分条数，作为下一页的 (max, offset) 回传给前端。
    // 例：本页分数 [5,4,4,2,2] → minTime=2，score==2 共 2 条 → 下次请求 (max=2, offset=2)。
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        // 收件箱是按登录用户隔离的，每个人都只看自己关注的人的笔记
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        // 收件箱与 saveBlog 推送时的 key 约定一致：FEED_KEY + 粉丝id
        String key = FEED_KEY + userId;
        // 首次请求 max 传当前时间戳、offset 传 0，之后前端带回上次的 minTime/offset
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                // ZREVRANGEBYSCORE：按 score 从大到小取，LIMIT offset 2 表示先跳过 offset 条
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 每页固定取 2 条便于演示；score 范围 [0, max]，跳过 offset 条与 max 同分的记录
        // 为什么不用页码分页：收件箱随时可能插入新笔记，页码翻页会重复或漏读；
        // 滚动分页以上一次的最小时间戳 minTime 为游标，实现不重不漏的增量读取
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        // minTime：本页最小时间戳，作为下一页查询的 max 游标
        long minTime = 0; // 2
        // os：与 minTime 同分的条数，作为下一页查询的 offset（推导见下）
        int os = 1; // 2
        // offset 公式推导：本页里 score 等于 minTime 的条数就是下一页要跳过的数量——
        // 下一页查询会以 minTime 作为新的 max，这些同分记录若不被 offset 排除会重复返回。
        // 例：本页分数 [5,4,4,2,2]，minTime=2，score==2 有 2 条 → 下次请求 (max=2, offset=2)
        // 遍历时统计“与 minTime 相同时间戳的条数”，遍历结束后的 os 即下一页 offset
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            // member 是笔记 id 字符串，转成 Long 存入列表
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            // score 是毫秒时间戳，取整即可比较
            long time = tuple.getScore().longValue();
            // 与当前 minTime 相同 → 同分计数 +1；否则刷新 minTime 并重置计数
            if(time == minTime){
                // 又遇到一条与 minTime 同分的记录
                os++;
            }else{
                // 出现新的最小时间戳，游标推进
                minTime = time;
                // 同分计数从 1 重新开始（当前这条就是同分的第一条）
                os = 1;
            }
        }

        // 5.根据id查询blog
        // IN 不保证顺序，用 ORDER BY FIELD 保持收件箱的时间戳倒序
        String idStr = StrUtil.join(",", ids);
        // 查出的 blogs 顺序与收件箱一致（新笔记在前）
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        // 与列表/详情一致：每条笔记补作者信息与当前用户点赞状态
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        // minTime/offset 原样封装，前端下次请求带回，实现滚动分页的游标传递
        ScrollResult r = new ScrollResult();
        // 本页笔记列表
        r.setList(blogs);
        // 下一页要跳过的同分条数
        r.setOffset(os);
        // 下一页查询的时间戳游标
        r.setMinTime(minTime);

        // 游标三件套（list/offset/minTime）一起返回给前端
        return Result.ok(r);
    }

    /** 补齐笔记的作者昵称与头像（Blog 表只存 userId，前端展示需要这两个字段）。 */
    // 这是典型的「冗余反填」：不建视图不联表，查询侧多一次主键回查，换来 SQL 简单。
    private void queryBlogUser(Blog blog) {
        // 作者 id 在发布时已写入 Blog 实体
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        // 昵称与头像挂到 blog 的冗余字段上，随实体一起返回
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
// —— 本类小结 ——
// 点赞：ZSet 一份数据同时支撑「是否点过」判断与点赞时间排行榜；
// 发布：推模式写扩散到每个粉丝的收件箱，粉丝多时发布慢、读快；
// Feed 滚动分页：minTime + offset 双游标解决「同分记录不重不漏」的增量读取问题。
// 若后续粉丝规模扩大，收件箱写扩散会成为瓶颈，可平滑切换到推拉结合模式。
