package com.hmdp.mapper;

import com.hmdp.entity.SeckillVoucher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 秒杀优惠券表（tb_seckill_voucher）的数据访问层 Mapper 接口。 */
/* 与优惠券表（tb_voucher）是一对一关系：一张普通券若开启秒杀， */
/* 会在这里补充 stock（库存）、begin_time / end_time（可抢购时间窗）等信息。 */
/* 继承 MyBatis-Plus 的 BaseMapper<SeckillVoucher> 后，按 voucher_id 查询秒杀 */
/* 信息、抢购成功后扣减库存（update stock = stock - 1 where voucher_id = ? */
/* and stock > 0 这类带库存条件的更新）均可通过框架注入的 updateById / */
/* update(Wrapper) 完成，库存条件由 UpdateWrapper 在业务层拼装以防止超卖， */
/* 故无需手写 SQL，接口体内也无需声明自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

}
