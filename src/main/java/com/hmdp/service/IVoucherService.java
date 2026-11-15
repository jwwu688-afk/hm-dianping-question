package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/** 优惠券业务的服务接口，对应实体 Voucher（tb_voucher 表）。 */
/* 继承 MyBatis-Plus 的 IService<Voucher> 获得通用 CRUD， */
/* 下列方法实现商铺券列表查询与秒杀券的新增落库。 */
/* @author jarvis  @since 2026-09-16 */
public interface IVoucherService extends IService<Voucher> {

    /** 查询指定商铺的优惠券列表。 */
    /* 底层调用 VoucherMapper#queryVoucherOfShop 左连接秒杀表， */
    /* 返回的每张券都带秒杀库存与起止时间（非秒杀券这些字段为空）， */
    /* 前端据此渲染"购买"或"抢购"。 */
    Result queryVoucherOfShop(Long shopId);

    /** 新增秒杀优惠券（管理端）。 */
    /* 在事务内先保存 tb_voucher 拿到券 id， */
    /* 再将库存、起止时间写入一对一的 tb_seckill_voucher， */
    /* 保证两张表同时成功或同时回滚。 */
    void addSeckillVoucher(Voucher voucher);
}
