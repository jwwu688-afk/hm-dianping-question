package com.hmdp.mapper;

import com.hmdp.entity.UserInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 用户详情信息表（tb_user_info）的数据访问层 Mapper 接口。 */
/* 负责用户扩展资料的持久化读写（城市、性别、生日、个人简介等更丰富的 */
/* 个人信息），与登录主表 tb_user 一对一，用于个人主页展示与资料编辑场景。 */
/* 继承 MyBatis-Plus 的 BaseMapper<UserInfo> 后，按用户 id 查详情、更新资料等 */
/* 单表操作均由框架注入的 selectById、updateById 等基础 CRUD 方法完成， */
/* 无需手写 SQL 或 XML 映射文件，接口体内无需声明任何自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface UserInfoMapper extends BaseMapper<UserInfo> {

}
