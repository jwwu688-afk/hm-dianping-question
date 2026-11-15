package com.hmdp.mapper;

import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 用户关注关系表（tb_follow）的数据访问层 Mapper 接口。 */
/* 一条记录代表"关注人 user_id 关注了 follow_user_id"， */
/* 是共同关注（求交集）、关注与粉丝列表以及 Feed 流推箱的底层数据。 */
/* 继承 MyBatis-Plus 的 BaseMapper<Follow> 后，关注与取关对应 insert/delete， */
/* 是否已关注对应按 user_id + follow_user_id 的条件查询， */
/* 这些都能由框架注入的基础 CRUD 方法配合 Wrapper 条件构造器完成， */
/* 因此无需手写 SQL，接口体内无需声明任何自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface FollowMapper extends BaseMapper<Follow> {

}
