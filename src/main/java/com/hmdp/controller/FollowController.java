package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author jarvis
 */
// ==================== 关注业务域控制器（路由前缀 /follow） ====================
// 职责：关注/取关、是否已关注查询、共同关注计算。
// 关注关系持久化在 tb_follow 表（user_id=关注人，follow_user_id=被关注人）；
// 同时在 Redis 维护"我的关注" Set，供共同关注 SINTER 求交集使用。
// 所有接口均要求登录：当前用户由拦截器链写入 ThreadLocal，经 UserHolder 提供；
// 业务逻辑均委托 IFollowService 实现，返回值统一为 Result 结构。
// 典型请求：PUT /follow/2/true 表示关注用户 2；
// 共同关注等读接口只查不写，关注/取关为写接口。
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /** 关注或取消关注用户：PUT /follow/{关注用户id}/{isFollow}。 */
    // 业务语义：isFollow=true 时在 tb_follow 新增关注关系，
    // false 时删除对应记录；
    // 同时同步维护 Redis 中"我的关注" Set，
    // 供共同关注求交集等功能使用；两个存储在 Service 内保持一致。
    // 路径参数 isFollow 直接取值 true/false，Spring 自动转 Boolean。
    // @param followUserId 被关注的用户 id
    // @param isFollow true=关注，false=取关
    // @return 操作结果
    // 注意：关注与取关都要求当前用户已登录，否则拦截器返回 401。
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    /** 查询当前登录用户是否已关注指定用户：GET /follow/or/not/{id}。 */
    // 业务语义：查 tb_follow 是否存在当前用户对目标用户的关注记录，
    // 用于前端渲染"关注/已关注"按钮的初始状态。
    // @param followUserId 目标用户 id
    // @return true/false 表示是否已关注
    // 返回 Result.ok(true/false)，前端据此切换按钮文案。
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    /** 查询与当前用户的共同关注列表：GET /follow/common/{目标用户id}。 */
    // 业务语义：把"我关注的用户 Set"与"目标用户关注的 Set"
    // 在 Redis 中求交集（SINTER），得到共同关注用户 id 集合，
    // 再查回用户 DTO 列表（含昵称/头像）。
    // @param id 目标用户 id
    // @return 共同关注的用户列表
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id){
        return followService.followCommons(id);
    }
}
