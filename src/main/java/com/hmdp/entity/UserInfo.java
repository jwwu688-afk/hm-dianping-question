package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

// 用户详细信息实体类——对应 MySQL 表 tb_user_info。
// 与 tb_user 一对一：tb_user 存登录账号信息，本表存个人主页展示的资料。
// 业务角色：个人主页展示（城市、介绍、生日等）与社交数据统计（粉丝/关注数）。
// fans 与 followee 在关注/取关时同步增减，与 tb_follow 记录保持一致。
// 该表数据展示在"我的"页面与用户主页，与登录账号信息分离便于扩展。
// 新用户注册时初始化一条默认记录，粉丝/关注数从 0 开始计数。
// 主页数据接口直接返回本表记录，敏感度低于 tb_user。
// 编辑资料接口写入 city/introduce/gender/birthday 等字段。
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
@TableName("tb_user_info")
public class UserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键，用户id
     */
    // 主键直接复用用户 ID（一对一关联 tb_user 的 id）
    // 一个用户只对应一条 UserInfo 记录
    // 查询个人主页信息时按该 ID 直接定位
    // 不使用独立自增 ID，避免多一层映射关系
    // 注册初始化时写入，与用户生命周期一致
    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    /**
     * 城市名称
     */
    // 用户所在城市，个人主页展示
    // 可用于"同城"类扩展功能的数据基础
    // 允许为空：用户可选择性填写
    // 城市名可自由填写，不做字典强约束
    private String city;

    /**
     * 个人介绍，不要超过128个字符
     */
    // 个性签名/自我介绍文本，前端限制 128 字符
    // 展示在主页头像下方
    // 超长由前端截断或提交校验拦截
    // 支持富文本前的纯文本场景
    private String introduce;

    /**
     * 粉丝数量
     */
    // 关注该用户的人数；被关注 +1，取关 -1
    // 展示在个人主页的粉丝数统计
    // 高并发下增减与 tb_follow 变更保持同步
    // 计数不精确时可按 tb_follow 重新统计校准
    // 展示时通常取整或缩写（如 1.2万）
    private Integer fans;

    /**
     * 关注的人的数量
     */
    // 该用户关注的人数；关注 +1，取关 -1
    // 与 fans 一起构成主页社交数据卡片
    // 与 fans 互为对偶：我关注的人进入对方的 fans
    // 两个计数的更新发生在同一次关注/取关事务内
    private Integer followee;

    /**
     * 性别，0：男，1：女
     */
    // 性别标记，用于主页展示
    // 使用 Boolean 包装类型，null 表示未填写
    // 前端按 true/false 映射男女图标
    // 资料编辑页提供选择项写入
    private Boolean gender;

    /**
     * 生日
     */
    // 出生日期，用于主页展示生日信息
    // 用 LocalDate 只保留日期、不含时分秒
    // 可用于生日祝福等运营活动
    // 允许为空：用户可不填
    private LocalDate birthday;

    /**
     * 积分
     */
    // 用户积分，签到等行为可累积
    // 积分体系可扩展兑换优惠券等玩法
    // 目前仅在签到模块累计
    // 扣减场景尚未开放，字段只增不减
    private Integer credits;

    /**
     * 会员级别，0~9级,0代表未开通会员
     */
    // 会员等级标记
    // 目前以 Boolean 简化建模，扩展多等级时建议改整型
    // 会员相关功能预留字段，暂无业务写入
    private Boolean level;

    /**
     * 创建时间
     */
    // 资料创建时间；随用户首次注册创建
    // 用户本人不可见，仅后台使用
    // 与 tb_user.createTime 应保持同值
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    // 资料最后修改时间；用户编辑主页信息时刷新
    // 编辑资料接口成功后同步更新
    // 无编辑行为时与 createTime 相同
    private LocalDateTime updateTime;


}
