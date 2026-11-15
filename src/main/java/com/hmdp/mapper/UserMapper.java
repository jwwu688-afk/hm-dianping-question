package com.hmdp.mapper;

import com.hmdp.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 用户表（tb_user）的数据访问层 Mapper 接口。 */
/* 负责登录用户的持久化读写：手机号验证码登录时按 phone 查询用户， */
/* 不存在则 insert 新用户完成注册；nick_name、icon 等字段用于博客、 */
/* 评论等场景的用户信息展示（脱敏后返回）。 */
/* 继承 MyBatis-Plus 的 BaseMapper<User> 后，按手机号查用户、按 id 批量查询 */
/* （selectBatchIds，用于拼装点赞与评论人信息）、insert 等操作 */
/* 均由框架注入的基础 CRUD 方法完成，无需手写 SQL 或 XML 映射文件， */
/* 接口体内无需声明任何自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface UserMapper extends BaseMapper<User> {

}
