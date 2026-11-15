package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/** 用户业务的服务接口，对应实体 User（tb_user 表）。 */
/* 继承 MyBatis-Plus 的 IService<User> 获得通用 CRUD， */
/* 下列方法实现基于手机验证码的登录与基于 Redis BitMap 的签到体系。 */
/* @author jarvis  @since 2026-09-16 */
public interface IUserService extends IService<User> {

    /** 发送短信验证码。 */
    /* 校验手机号格式后生成 6 位验证码，存入 Session 供登录时比对 */
    /* （实现中也会通过日志或第三方通道发出），并以 Result 提示发送成功。 */
    Result sendCode(String phone, HttpSession session);

    /** 验证码登录或注册。 */
    /* 比对 Session 中验证码，然后按 phone 查用户，不存在则自动注册 */
    /* （初始化昵称与随机头像）；随后生成随机 token 作为登录令牌， */
    /* 将用户信息以 JSON 字符串形式写入 Redis（key = login:token， */
    /* 设过期时间）并刷新令牌有效期，返回 token 给前端携带。 */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /** 今日签到。 */
    /* 以用户 id 与月份构造 Redis BitMap（key = sign:用户id:yyyyMM）， */
    /* 将当天对应比特位置 1（setBit），实现一个用户一个月仅占用 */
    /* 31 个比特的极省空间签到。 */
    Result sign();

    /** 查询当前用户的连续签到天数。 */
    /* 取本月 BitMap 从今天往前按位统计（BITFIELD 加位与运算）， */
    /* 遇到第一个未签到的比特即停止，返回连续签到天数。 */
    Result signCount();

}
