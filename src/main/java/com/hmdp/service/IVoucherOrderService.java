package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/** 优惠券订单业务的服务接口，对应实体 VoucherOrder（tb_voucher_order 表）。 */
/* 继承 MyBatis-Plus 的 IService<VoucherOrder> 获得通用 CRUD， */
/* seckillVoucher 是秒杀下单的统一入口。 */
/* @author jarvis  @since 2026-09-16 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /** 秒杀下单入口：抢购指定优惠券。 */
    /* 业务流程：校验当前时间是否在秒杀时间窗内，然后扣减库存 */
    /* （update tb_seckill_voucher set stock = stock - 1 where id = ? */
    /* and stock > 0，依靠数据库条件更新防超卖），再校验"一人一单" */
    /* （同一用户对同一券只允许一单，通过用户级锁加事务保证并发下不重复 */
    /* 下单），最后插入 tb_voucher_order 订单。 */
    /* 实现上可采用 Lua 脚本预判加阻塞队列异步下单的优化方案， */
    /* 以提升高并发场景下的吞吐。 */
    Result seckillVoucher(Long voucherId);
}
