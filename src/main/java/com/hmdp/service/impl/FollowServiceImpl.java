package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// —— 关注关系的数据模型 ——
// tb_follow 表：user_id（关注者）、follow_user_id（被关注者），一条记录一条关注关系；
// Redis Set follows:用户id：该用户关注的对象 id 集合，专为集合运算（交集）而生。
// 两份数据由本类在写路径上同步维护，读路径按需选择数据源。
// 注意：Redis 侧数据由 follow()/unfollow 写路径维护，不设 TTL，与库同生命周期。

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * <p>关注服务：关注/取关双写（MySQL + Redis Set），共同关注用 SINTER 求交集。</p>
 *
 * @author jarvis
 * @since 2026-09-16
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    // Redis 客户端：维护每个用户的关注 Set（key = follows:用户id）
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // 用户服务：共同关注列表需要回查用户信息
    @Resource
    private IUserService userService;

    // —— 双写设计 ——
    // MySQL 的 tb_follow 表是权威数据：可分页、可统计、有事务保障；
    // Redis Set 是读缓存：为「共同关注」这类高频集合运算提速。
    // key 约定：follows:用户id，member 是该用户关注的对象 id。
    // 写入顺序固定「先库后缓存」，保证缓存里只出现确实落库的关注关系。
    // 缓存中不存取关动作的痕迹：Set 里没有 = 没有关注，语义天然一致。

    /** 关注 / 取关；双写与顺序设计见上方与方法内注释。 */
    // 入参 isFollow 是前端按钮的意图，不是当前状态：接口语义是「设置为关注/取消」。
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取登录用户
        // 关注动作的发起者就是当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 当前用户的关注集合 key，关注与取关都要操作它
        String key = "follows:" + userId;
        // 1.判断到底是关注还是取关
        // isFollow 由前端按钮状态决定：点「关注」传 true，点「已关注（取消）」传 false
        if (isFollow) {
            // 2.关注，新增数据
            // 组装关注关系实体：谁（userId）关注了谁（followUserId）
            Follow follow = new Follow();
            // user_id：关注动作的发起者
            follow.setUserId(userId);
            // follow_user_id：被关注的博主
            follow.setFollowUserId(followUserId);
            // 先落库：数据库是权威数据
            boolean isSuccess = save(follow);
            // 库写成功才动 Redis；失败则两份数据都不动，不会出现半截状态
            if (isSuccess) {
                // 把关注用户的id，放入redis的set集合 sadd userId followerUserId
                // Set 天然去重，重复关注也不会产生脏数据
                // member 直接存 followUserId 字符串，共同关注时按 id 解析
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 3.取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
            // 双条件精确删除，避免误删其他关注关系
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            // 与关注分支同理：先库后缓存，失败方向一致
            if (isSuccess) {
                // 把关注用户的id从Redis集合中移除
                // 库删成功才删缓存，两份数据的失败方向保持一致
                // SREM 只删这一个 member，不影响该用户的其他关注
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        // 无论关注还是取关，操作完成即返回成功
        // 双写没有包事务：Redis 侧失败只影响共同关注功能，权威数据仍在表中
        return Result.ok();
    }

    /** 判断当前登录用户是否关注了指定用户；为什么查表不查缓存见方法内注释。 */
    // 这个判断用在个人主页等低频场景，直接查权威表更稳妥；
    // 高频的共同关注运算才需要 Redis Set 提速，两者各司其职。
    // 若页面需要高频判断，可改为先查 Set 再兜底查库。
    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        // count 大于 0 即存在这条关注关系
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.判断
        // 只关心「有没有」，count 的具体数值无意义
        // 前端据此渲染按钮：true 显示「已关注」，false 显示「关注」
        return Result.ok(count > 0);
    }

    /** 求当前用户与目标用户的共同关注列表；SINTER 的用法见方法内注释。 */
    // 为什么用 Redis 求交集：MySQL 要对 tb_follow 做自连接或两次子查询再求交，
    // Redis 的 SINTER 一次命令在内存中完成，快且实现简单。
    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        // 共同关注的计算双方：当前登录用户 与 页面上的目标用户 id
        Long userId = UserHolder.getUser().getId();
        // 当前用户的关注集合
        String key = "follows:" + userId;
        // 2.求交集
        // 对方的关注集合，key 规则与 follow() 写入时一致
        String key2 = "follows:" + id;
        // SINTER 求两个关注集合的交集，交集里的 member 就是共同关注的用户 id
        // 复杂度对比：MySQL 自连接 O(N*N) 索引查找；SINTER 在内存中一次完成
        // 这也是关注关系要额外维护一份 Set 的根本原因
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        // 交集为空说明没有共同关注，直接返回空列表
        // 不再回查数据库，避免无意义的 IO
        // null 与空集合同等对待，调用方统一处理
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        // Set 里的 member 是字符串，转成 Long 才能去查用户表
        // 顺序无关紧要：共同关注列表不要求特定排序
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        // 交集只有 id，昵称头像还得回 MySQL 查一次并裁剪成 UserDTO
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                // 实体转 DTO，只暴露展示字段
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                // 收集为列表返回
                .collect(Collectors.toList());
        // 共同关注列表返回给前端展示
        // DTO 只含 id/昵称/头像，前端直接渲染头像墙
        // 数量一般很少（几个到几十个），一次查回没有分页必要
        return Result.ok(users);
    }
}
// —— 本类小结 ——
// follow/isFollow：数据库为权威，Redis Set 为读缓存，先库后缓存保持一致；
// followCommons：SINTER 一次命令求交集，把集合运算下推到 Redis 内存中完成。
// 取关时缓存与库同步删除，Set 语义「在集合里 = 已关注」始终成立。
