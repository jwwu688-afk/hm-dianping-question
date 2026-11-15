package com.hmdp.dto;

import lombok.Data;

// 登录表单 DTO —— 接收前端登录/注册页提交的参数。
// 手机验证码登录时只需要 phone + code；账号密码登录时
// 使用 phone + password。表单数据单独建 DTO 接收，不与
// 实体类 User 耦合，也便于做参数格式校验。
// 使用场景：UserController 的 login() 方法以该对象接收请求体。
@Data
public class LoginFormDTO {
    /** 手机号，作为用户的唯一登录标识（服务端用正则校验格式） */
    private String phone;
    /** 短信验证码，与 Redis 中 login:code:{phone} 保存的验证码比对 */
    private String code;
    /** 密码，账号密码登录方式时提交，验证码登录时不传 */
    private String password;
}
