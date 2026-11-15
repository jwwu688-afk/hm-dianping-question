package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

// 用户实体类——对应 MySQL 表 tb_user，存储用户的完整账号信息。
// 业务角色：
// 1. 验证码登录：按手机号查询用户，不存在则自动注册（随机昵称 user_xxx）；
// 2. 登录成功后裁剪为 UserDTO 存入 Redis（login:token），本实体不直接出参；
// 3. 密码经 PasswordEncoder 加盐哈希后存储，数据库不存明文。
// 注意：本实体含手机号、密码等敏感字段，禁止直接序列化返回给前端。
// 典型接口：UserController.login() 登录注册、me() 获取当前用户。
// 对外返回时统一转成 UserDTO，只保留 id、nickName、icon。
// 登录态存 Redis Hash：field 与本类字段对应，取值时 fill 到 UserDTO。
// 注册入口：验证码登录未命中用户时自动插入本表记录。
// 主键生成策略：数据库自增，写入后回填到实体。
/**
 * <p>
 *
 * </p>
 *
 * @author jarvis
 * @since 2026-09-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    // 用户 ID，自增主键；是点赞、关注、订单等业务关联的核心标识
    // 新用户注册成功后即可凭它参与全部业务
    // 存入 Redis 登录态与 UserDTO 时都以此为唯一键
    // 各业务表（订单、博客、关注）通过它关联到用户
    // ID 趋势递增，对聚簇索引友好
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 手机号码
     */
    // 登录标识，一个手机号对应一个用户（业务上唯一）
    // 敏感字段，对外展示时随 UserDTO 裁剪掉
    // 登录前先用 RegexPatterns.PHONE_REGEX 校验格式
    // 短信验证码发送与登录都依赖该手机号
    // 查询时按 phone 建索引，登录命中更快
    // 一个手机号仅允许注册一个账号
    private String phone;

    /**
     * 密码，加密存储
     */
    // 加盐 MD5 后的密文（PasswordEncoder.encode），格式：盐@摘要
    // 账号密码登录时用它做 matches 比对
    // 验证码登录时不校验该字段，可为 null
    // 密码长度由 PASSWORD_REGEX 限制 4~32 位
    private String password;

    /**
     * 昵称，默认是随机字符
     */
    // 展示名；注册时自动生成 user_ 前缀随机昵称，用户可修改
    // 该字段会随 UserDTO 返回前端展示
    // 昵称不唯一，仅作展示用途
    // 前端博客评论、点赞列表都展示该昵称
    private String nickName;

    /**
     * 用户头像
     */
    // 头像图片地址，默认空串；头像上传后指向静态资源路径
    // 字段初始化为 ""，避免前端拿到 null 显示异常
    // 上传走通用图片接口，返回相对路径存入该字段
    private String icon = "";

    /**
     * 创建时间
     */
    // 注册时间；可用于统计新增用户趋势
    // 注册即创建，之后不再变化
    // 与 updateTime 分离，便于区分注册与修改行为
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    // 资料最后修改时间（昵称、头像变更时刷新）
    // MyBatis-Plus 更新时若字段非空会覆盖写入
    // 无修改行为时该值与 createTime 相同
    private LocalDateTime updateTime;


}
