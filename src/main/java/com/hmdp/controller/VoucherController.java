package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author jarvis
 */
// ==================== 优惠券业务域控制器（路由前缀 /voucher） ====================
// 职责：普通券与秒杀券的新增、店铺优惠券列表查询。
// 数据模型：tb_voucher 存基础信息；秒杀券额外在 tb_seckill_voucher
// 写入库存与起止时间，为 VoucherOrderController 的秒杀下单做准备。
// 管理类接口（新增）面向运营后台；查询接口面向 C 端用户。
// 注意 /voucher/** 在登录白名单中，浏览优惠券不需要登录。
// 新增接口不校验调用者身份，部署时应由网关或运维侧限制来源。
// 秒杀券创建后会进入 Redis 预扣体系（见 VoucherOrderServiceImpl）。
// 优惠券不支持删除/下架接口，如需回收应做状态字段扩展。
// 本控制器只做路由承接，业务细节都在 IVoucherService 实现类中。
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    // 接口：新增秒杀券 POST /voucher/seckill（JSON 请求体）。
    // 业务语义：保存券基本信息的同时，若携带秒杀字段则在
    // tb_seckill_voucher 写入库存与起止时间；
    // 秒杀下单入口见 VoucherOrderController，本接口不涉及库存扣减。
    // 库存数据是后续 Lua 预扣的热点数据，运营应提前创建避免临时加压。
    // @param voucher 优惠券信息，包含秒杀信息
    // @return 优惠券id
    // 无独立校验：起止时间与库存的合法性由运营侧保证。
    // 秒杀券 id 即后续下单接口的路径参数，创建后不可变更。
    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        // 秒杀信息随 voucher 对象一起传入 Service 处理（含事务）
        voucherService.addSeckillVoucher(voucher);
        // 新增成功返回券 id，供运营后台跳转
        return Result.ok(voucher.getId());
    }

    // 接口：新增普通券 POST /voucher（JSON 请求体）。
    // 业务语义：仅保存 tb_voucher 基础信息，
    // 无库存与起止时间概念，用户可直接领取使用。
    // 与秒杀券接口的区别：不写 tb_seckill_voucher。
    // @param voucher 优惠券信息
    // @return 优惠券id
    // 普通券数量不限领用，业务上仅做展示与核销记录。
    // 券的核销与退券当前无独立接口，由后续订单模块扩展。
    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        // save 为 MyBatis-Plus 的落库方法，主键自动回填
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }


    // 接口：查询店铺优惠券列表 GET /voucher/list/{shopId}。
    // 业务语义：返回该店铺可用的普通券与秒杀券列表，
    // 供商铺详情页"优惠券"栏目展示；
    // 秒杀券附带库存与起止时间，前端据此渲染秒杀倒计时。
    // @param shopId 店铺id
    // @return 优惠券列表
    // 无分页：单个店铺的券数量有限，一次返回全量。
    // 按创建时间倒序返回，最新创建的券展示在前。
    // 秒杀中的券前端会禁用"立即购买"以外的操作入口。
    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       // 返回列表含普通券与秒杀券，前端按类型区分展示
       return voucherService.queryVoucherOfShop(shopId);
    }
}
