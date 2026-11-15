package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

// 代金券实体类——对应 MySQL 表 tb_voucher。
// 商铺发行的优惠券：type=0 普通券直接购买，type=1 秒杀券附带秒杀信息。
// 业务角色：
// 1. 券列表/详情展示：payValue 与 actualValue 展示"原价/优惠价"；
// 2. 下单购买：普通券直接创建 tb_voucher_order；
// 3. 秒杀券的库存与时间窗口存 tb_seckill_voucher，
//    通过 @TableField(exist = false) 的 stock/beginTime/endTime 联合展示。
// 金额字段以"分"为单位存储，展示时前端转换为元。
// 普通券与秒杀券共用本表，秒杀属性由 tb_seckill_voucher 扩展。
// 新增秒杀券时两张表同事务写入，保证一致。
/**
 * <p>
 *
 * </p>
 *
 * @author jarvis
 * @since 2026-09-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_voucher")
public class Voucher implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    // 券 ID，自增主键；秒杀券复用它作为 tb_seckill_voucher 的主键
    // 订单表 voucher_id 关联本字段
    // 详情页按 id 直查（可走缓存）
    // 普通券与秒杀券的关联基础
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商铺id
     */
    // 发券商铺的 ID，关联 tb_shop；券只能在该商铺消费
    // 券列表按商铺查询展示
    // 商铺详情页可拉取该店全部券
    private Long shopId;

    /**
     * 代金券标题
     */
    // 券的主标题，如"50元代金券"，列表页主展示文案
    // 新增券时由商家填写
    // 展示时加¥符号由前端处理
    private String title;

    /**
     * 副标题
     */
    // 券副标题，补充说明卖点（如"周末通用"）
    // 展示在标题下方的小字
    // 可为空
    private String subTitle;

    /**
     * 使用规则
     */
    // 使用规则文本：有效期、限制条件等说明
    // 详情页"使用须知"区块展示
    // 长文本，前端换行展示
    private String rules;

    /**
     * 支付金额
     */
    // 用户购买该券需支付的金额（单位：分），避免小数精度问题
    // 与 actualValue 构成"花 x 元抵 y 元"话术
    // 用 Long 而非 Double，杜绝浮点误差
    private Long payValue;

    /**
     * 抵扣金额
     */
    // 到店消费可抵扣的金额（单位：分），通常大于 payValue
    // 差值即用户感知的优惠力度
    // 展示时除以 100 转为元
    private Long actualValue;

    /**
     * 优惠券类型
     */
    // 券类型：0 普通券；1 秒杀券
    // 前端按类型展示"抢购"或"购买"按钮
    // 下单流程按类型走不同分支
    private Integer type;

    /**
     * 优惠券类型
     */
    // 券状态：控制是否可售/下架等售卖状态
    // 原注释与 type 重复，实际语义为售卖状态
    // 下架券不再出现在可购列表
    private Integer status;
    /**
     * 库存
     */
    // 秒杀库存：非本表字段，来自 tb_seckill_voucher.stock
    // 列表页联查秒杀表填充，用于展示"仅剩 x 件"
    // 普通券该字段为 null
    // 与秒杀表的 stock 同源，不重复存储
    @TableField(exist = false)
    private Integer stock;

    /**
     * 生效时间
     */
    // 秒杀开始时间：非本表字段，来自 tb_seckill_voucher.begin_time
    // 用于前端秒杀倒计时展示
    // 未开始时下单按钮置灰
    @TableField(exist = false)
    private LocalDateTime beginTime;

    /**
     * 失效时间
     */
    // 秒杀结束时间：非本表字段，来自 tb_seckill_voucher.end_time
    // 前端据此渲染秒杀倒计时
    // 与 beginTime 共同构成活动窗口
    @TableField(exist = false)
    private LocalDateTime endTime;

    /**
     * 创建时间
     */
    // 券创建时间
    // 后台新增券时写入
    // 新增秒杀券时同事务写秒杀表
    private LocalDateTime createTime;


    /**
     * 更新时间
     */
    // 券最后修改时间
    // 调整价格、上下架时刷新
    // 精确到秒级
    private LocalDateTime updateTime;


}
