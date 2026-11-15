package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author jarvis
 */
// ==================== 优惠券订单业务域控制器（路由前缀 /voucher-order） ====================
// 当前只保留秒杀下单入口：Redis Lua 完成库存与一人一单预扣后，
// 订单消息发送到 RabbitMQ（拓扑见 OrderRabbitConfig），由消费者异步
// 落库；接口立即返回订单 id，不等 MySQL 写入完成（最终一致）。
// 历史上曾用 Redis Stream + 消费组实现异步下单，现已被 RabbitMQ 方案替代。
// 一人一单与库存校验全部前置到 Redis（Lua 脚本原子执行），
// MySQL 只在消费端做扣减与插入，吞吐能力由 Redis 决定。
// 接口幂等性弱：同一用户重复请求会被 Lua 的一人一单校验拦截。
// /voucher-order/** 需要登录，未登录返回 401。
// 前端拿到订单 id 后轮询订单列表确认出票结果。
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /** 秒杀券下单：POST /voucher-order/seckill/{券id}。 */
    // 业务语义：校验登录态与券 id 后，执行 Lua 脚本在 Redis 中
    // 预扣库存并校验一人一单（返回码 0 成功/1 无库存/2 重复购买/
    // 3 库存未初始化/4 数据异常）；
    // 成功即把订单发送到 RabbitMQ 并返回订单 id，
    // MySQL 扣库存与插单由消费者异步完成。
    // 路径参数 id 为数字型 Long，天然防止 Lua 注入类攻击。
    // 返回 ok 仅代表"受理成功"（消息已发出），不代表出票完成。
    // 库存不足/重复购买等以业务失败 Result 返回，不抛异常。
    // 与旧方案差异：不再检查活动起止时间（Lua 脚本已简化）。
    // 接口无请求体，券上下文完全由路径参数提供。
    // @param voucherId 秒杀券 id
    // @return 受理结果（成功时含订单 id）
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        // 全部业务逻辑委托 Service：校验 → Lua 预扣 → 发送 MQ
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
