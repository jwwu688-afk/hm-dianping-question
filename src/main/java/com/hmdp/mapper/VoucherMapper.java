package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Voucher;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 优惠券表（tb_voucher）的数据访问层 Mapper 接口。 */
/* 负责商铺代金券的持久化读写：普通券只存标题、副标题、规则、原价与售价； */
/* 秒杀券额外在 tb_seckill_voucher 中补充库存与时间窗，两表通过 voucher_id */
/* 一对一关联。继承 MyBatis-Plus 的 BaseMapper<Voucher> 后，新增券（insert）、 */
/* 按主键查券（selectById）等单表操作由框架自动注入，无需手写基础 CRUD 的 */
/* SQL；仅跨表查询才需要自定义方法（见下方 queryVoucherOfShop）。 */
/* @author jarvis  @since 2026-09-16 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    /** 查询指定商铺的优惠券列表（普通券 + 秒杀券）。 */
    /* SQL 语义：以 tb_voucher 为主表按 shop_id 过滤并排序， */
    /* 左连接 tb_seckill_voucher（on seckill_voucher.voucher_id = voucher.id）， */
    /* 把秒杀库存、起止时间一并封装进返回的 Voucher 中， */
    /* 前端据此区分普通购买与立即抢购按钮。 */
    /* 映射 XML 位于 resources/mapper 下；@Param 将 shopId 绑定进 SQL 占位符。 */
    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
