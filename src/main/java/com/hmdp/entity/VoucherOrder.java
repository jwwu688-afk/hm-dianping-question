package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

// 优惠券订单实体类——对应 MySQL 表 tb_voucher_order。
// 记录用户购买代金券（普通券/秒杀券）产生的订单。
// 业务角色：
// 1. 秒杀下单最终落库到本表（异步消费阶段写入）；
// 2. 一人一单校验：按 (userId, voucherId) 查询是否已有订单；
// 3. 订单 ID 由 RedisIdWorker 生成（防枚举、全局唯一），不用数据库自增。
// 并发安全：秒杀场景下单使用分布式锁/Lua 资格校验，保证不超卖、不重复下单。
// 订单状态机：未支付 → 已支付 → 已核销为主线，取消/退款为分支。
// 高并发下本表写入集中在异步消费阶段，压力被消息队列削峰。
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
@TableName("tb_voucher_order")
public class VoucherOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    // 订单号，主键由业务生成（IdType.INPUT 手动指定）
    // 使用 RedisIdWorker.nextId("order:") 生成：
    // 时间戳部分趋势递增利于索引，序列号部分保证全局唯一
    // 不用自增 ID 的原因：防止订单量被外部枚举探测
    // ID 生成失败时下单直接失败，不落库
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 下单的用户id
     */
    // 购买者用户 ID，关联 tb_user
    // 一人一单判断的关键字段之一
    // 订单列表按用户查询时以此为过滤条件
    // 我的活动订单页按它检索
    private Long userId;

    /**
     * 购买的代金券id
     */
    // 被购买的优惠券 ID，关联 tb_voucher
    // (userId, voucherId) 组合用于秒杀"一人一单"校验
    // 同时写入 Redis seckill:order 集合做快速资格判断
    // 普通券与秒杀券共用本表
    private Long voucherId;

    /**
     * 支付方式 1：余额支付；2：支付宝；3：微信
     */
    // 支付方式枚举：1 余额支付、2 支付宝、3 微信
    // 教学项目默认置 1，未接入真实支付
    // 支付对接后按渠道写入对应枚举值
    private Integer payType;

    /**
     * 订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款
     */
    // 订单状态枚举：1 未支付 → 2 已支付 → 3 已核销；
    // 4 已取消；5 退款中；6 已退款
    // 状态机流转由支付/核销/退款流程驱动
    // 状态非法跳转需在业务层拦截
    private Integer status;

    /**
     * 下单时间
     */
    // 订单创建时间，秒杀下单成功即写入
    // 异步下单时取消息生成时刻
    // 与订单号的时间戳部分接近
    private LocalDateTime createTime;

    /**
     * 支付时间
     */
    // 用户完成支付的时间；status 变为已支付时写入
    // 未支付订单该字段为 null
    // 支付超时未处理可转入取消流程
    private LocalDateTime payTime;

    /**
     * 核销时间
     */
    // 到店核销时间；status 变为已核销时写入
    // 券使用后不可再次核销
    // 商家端扫码核销时写入
    private LocalDateTime useTime;

    /**
     * 退款时间
     */
    // 退款完成时间；status 变为已退款时写入
    // 退款中状态时该字段仍为 null
    private LocalDateTime refundTime;

    /**
     * 更新时间
     */
    // 记录最后更新时间，任何状态流转都会刷新
    // 便于排查订单异常时定位最后变更时刻
    // 由各状态更新接口维护
    private LocalDateTime updateTime;


}
