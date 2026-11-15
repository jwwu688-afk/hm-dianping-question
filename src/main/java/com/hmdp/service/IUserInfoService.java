package com.hmdp.service;

import com.hmdp.entity.UserInfo;
import com.baomidou.mybatisplus.extension.service.IService;

/** 用户详情信息业务的服务接口，对应实体 UserInfo（tb_user_info 表）。 */
/* 继承 MyBatis-Plus 的 IService<UserInfo> 后，个人主页的详情查询、 */
/* 资料修改等单表操作直接复用框架提供的 getById、updateById、save 等通用 */
/* 方法，因此本接口无需声明任何自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface IUserInfoService extends IService<UserInfo> {

}
