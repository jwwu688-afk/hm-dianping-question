package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/** 用户关注业务的服务接口，对应实体 Follow（tb_follow 表）。 */
/* 继承 MyBatis-Plus 的 IService<Follow> 获得通用 CRUD， */
/* 下列方法实现关注与取关、状态查询与共同关注（依赖 Redis Set 求交集）。 */
/* @author jarvis  @since 2026-09-16 */
public interface IFollowService extends IService<Follow> {

    /** 关注或取关指定用户。 */
    /* isFollow 为 true：向 tb_follow 插入关注记录，并把关注人放入对方的 */
    /* Redis 关注 Set（key = follows:用户id）；为 false：删除记录并同步移出 */
    /* Set，数据库与缓存两侧保持一致，共同关注功能依赖该 Set。 */
    Result follow(Long followUserId, Boolean isFollow);

    /** 查询当前用户是否已关注指定用户。 */
    /* 按 user_id + follow_user_id 条件查询 tb_follow 记录数， */
    /* 大于 0 即已关注。 */
    Result isFollow(Long followUserId);

    /** 查询当前用户与目标用户的共同关注列表。 */
    /* 对双方的 Redis 关注 Set 做交集（SINTERSTORE 到临时 key）， */
    /* 取交集结果中的用户 id 批量查库并脱敏返回用户信息。 */
    Result followCommons(Long id);
}
