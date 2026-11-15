package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 秒杀优惠券业务的服务实现类。 */
/* 本类为空壳实现：继承 MyBatis-Plus 的 ServiceImpl 后，框架在运行期 */
/* 已按泛型参数提供全部通用能力——第一个泛型 SeckillVoucherMapper 指向 */
/* 数据表 tb_seckill_voucher（数据访问层），第二个泛型 SeckillVoucher 是 */
/* 与该表一一映射的实体类。按券 id 查秒杀信息（getById）、下单扣库存 */
/* （update 配合 UpdateWrapper 实现 stock = stock - 1 and stock > 0 的条件 */
/* 更新，由数据库行锁保证原子性、防止超卖）等操作均由父类方法直接覆盖， */
/* 秒杀下单主流程位于 VoucherOrderServiceImpl，本类无需额外定制，故类体为空。 */
/* @author jarvis  @since 2026-09-16 */
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {

}
