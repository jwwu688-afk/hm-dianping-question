package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

// 秒杀优惠券实体类——对应 MySQL 表 tb_seckill_voucher。
// 与优惠券表 tb_voucher 一对一：普通券只需 tb_voucher 一条记录，
// 参与秒杀活动的券额外在此表扩展秒杀属性。
// 业务角色：
// 1. 下单秒杀券前校验：当前时间是否在 [beginTime, endTime] 秒杀窗口内；
// 2. 扣减库存：UPDATE ... SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0；
// 3. 订单创建后把库存同步到 Redis（seckill:stock:{voucherId}），
//    由 Lua 脚本判断秒杀资格（库存充足 + 一人一单）再异步下单。
// 秒杀入口在 VoucherOrderController.seckillVoucher()，
// 该实体是超卖防护与时间窗口校验的核心数据。
// 秒杀流程概述：校验时间窗口 -> Redis Lua 预判资格 -> 发消息给队列
// -> 后台消费消息完成 DB 扣库存与订单落库。
// 与 Voucher 一对一，秒杀属性独立成表避免影响普通券查询。
/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系
 * </p>
 *
 * @author jarvis
 * @since 2026-09-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_voucher")
public class SeckillVoucher implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 关联的优惠券的id
     */
    // 主键即优惠券 ID（INPUT 手动指定），不再单独自增，
    // 保证与 tb_voucher 一对一对应
    // 查询秒杀详情时用它关联 tb_voucher 拿到券的基本信息
    // 新增秒杀券时由业务代码手动填入 tb_voucher 的主键值
    // 该设计保证两表记录一一对应、无冗余自增 ID
    @TableId(value = "voucher_id", type = IdType.INPUT)
    private Long voucherId;

    /**
     * 库存
     */
    // 该秒杀券的可售数量；高并发扣减时数据库用乐观方式
    // （WHERE stock > 0）防超卖，入口处先在 Redis 预扣
    // stock 减到 0 后秒杀结束
    // 库存变更需同步刷新 Redis 中的 seckill:stock 缓存值
    // 数据库扣减发生在异步消费阶段，与 Redis 预扣存在短暂不一致
    // 最终以数据库扣减成功为准，Redis 异常时可人工校准
    private Integer stock;

    /**
     * 创建时间
     */
    // 秒杀活动记录创建时间；用于后台活动管理列表
    // 创建即生成记录，与券的售卖状态无关
    // 后台列表按创建时间倒序展示活动
    private LocalDateTime createTime;

    /**
     * 生效时间
     */
    // 秒杀开始时间：早于此时间的请求会被拒绝（"活动尚未开始"）
    // 预热阶段会把库存加载进 Redis，等待开抢
    // 前端可根据该时间做倒计时展示
    private LocalDateTime beginTime;

    /**
     * 失效时间
     */
    // 秒杀结束时间：晚于此时间不再允许下单（"活动已结束"）
    // [beginTime, endTime] 共同构成秒杀活动的时间窗口
    // 下单前两者都校验，全部通过才允许进入扣库存流程
    // 结束后 Redis 中残留的资格数据可定期清理
    private LocalDateTime endTime;

    /**
     * 更新时间
     */
    // 记录最后修改时间（如库存扣减、活动调整）
    private LocalDateTime updateTime;


}
