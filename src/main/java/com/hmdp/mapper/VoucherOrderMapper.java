package com.hmdp.mapper;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 优惠券订单表（tb_voucher_order）的数据访问层 Mapper 接口。 */
/* 负责秒杀抢购订单的持久化写入：用户抢券成功即插入一条订单， */
/* 记录 user_id、voucher_id、下单时间与支付状态；"一人一单"的校验 */
/* 也依赖按 user_id + voucher_id 的条件查询此表。 */
/* 继承 MyBatis-Plus 的 BaseMapper<VoucherOrder> 后，插入订单、按用户加券查重 */
/* 等单表操作均由框架注入的 insert、selectCount(Wrapper) 等基础 CRUD 方法 */
/* 完成，无需手写 SQL 或 XML 映射文件，接口体内无需声明任何自定义方法。 */
/* @author jarvis  @since 2026-09-16 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

}
