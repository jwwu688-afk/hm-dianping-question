package com.hmdp.dto;

import lombok.Data;

// 用户信息 DTO —— 对外（前端）展示用的"裁剪版"用户对象。
// 登录成功后并不直接返回 User 实体，而是将其裁剪为该 DTO：
// 只保留 id、昵称、头像三个非敏感字段，避免把手机号、密码等
// 敏感信息暴露给前端。该对象会被存入 Redis（login:token）并在
// 拦截器中写入 ThreadLocal，供各业务获取当前登录用户。
// 对应实体：com.hmdp.entity.User（只保留三个安全字段）。
@Data
public class UserDTO {
    /** 用户 ID，前端凭它做点赞、关注等关联请求 */
    private Long id;
    /** 用户昵称，用于页面展示 */
    private String nickName;
    /** 用户头像地址，用于页面展示 */
    private String icon;
}
