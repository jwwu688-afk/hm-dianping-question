package com.hmdp.service;

import com.hmdp.entity.SeckillVoucher;
import com.baomidou.mybatisplus.extension.service.IService;

/** 秒杀优惠券业务的服务接口，对应实体 SeckillVoucher（tb_seckill_voucher 表）。 */
/* 与优惠券（tb_voucher）一对一：补充库存 stock 与可抢购时间窗 begin_time、 */
/* end_time。继承 MyBatis-Plus 的 IService<SeckillVoucher> 后，按券 id 查秒杀 */
/* 信息（getById）、下单后扣减库存（update 配合 UpdateWrapper 设置 */
/* stock = stock - 1 and stock > 0，依靠数据库行锁保证扣减原子性、防止超卖）、 */
/* 保存秒杀信息（save）等操作均直接复用框架通用方法， */
/* 因此本接口无需声明任何自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface ISeckillVoucherService extends IService<SeckillVoucher> {

}
