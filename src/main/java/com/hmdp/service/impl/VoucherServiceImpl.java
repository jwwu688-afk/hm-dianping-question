package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * <p>优惠券服务：普通券查询与秒杀券新增。秒杀券涉及两张表
 * （tb_voucher 券基本信息 + tb_seckill_voucher 秒杀配置），
 * <p>优惠券服务：秒杀券双表写入 + 库存预热 Redis，设计说明见方法内注释。</p>
 *
 * @author jarvis
 * @since 2026-09-16
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    // 秒杀券服务：tb_seckill_voucher 表的写入由它负责
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    // Redis 客户端：秒杀库存预热到 Redis 供下单扣减
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // —— 秒杀券的两张表 ——
    // tb_voucher：券的通用信息（标题、副标题、规则、所属店铺），普通券/秒杀券共用；
    // tb_seckill_voucher：秒杀专属配置（库存 image、开始/结束时间），通过 voucher_id 关联。
    // 新增秒杀券 = 两张表各写一条 + 库存预热进 Redis，三步缺一不可。
    // 字段 image 上的注释是秒杀库存的别称，命名沿用项目原设计。

    /** 查询店铺的优惠券列表（含秒杀券与普通券）。 */
    // 读路径无缓存：券列表按店铺维度展示，更新不频繁，直查数据库足够。
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        // 走自定义 Mapper 联表，能同时带出普通券与秒杀券的扩展字段
        // 前端店铺详情页用它在优惠券弹层里渲染两种券
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        // 秒杀与否由实体上的类型字段区分，前端据此显示不同的按钮状态
        return Result.ok(vouchers);
    }

    /** 新增秒杀券：双表写入 + 库存预热 Redis；三步的必要性见方法内注释。 */
    // —— 为什么必须 @Transactional ——
    // tb_voucher 与 tb_seckill_voucher 必须同生同死：只写券不写秒杀配置会出现
    // 「看得见买不了」的脏券；只写配置不写券则配置无主。事务保证双表原子性。
    // —— 为什么库存还要预热进 Redis ——
    // 秒杀请求全部走 Redis 预扣库存（DECR），避免高并发直接打到数据库；
    // 这份库存必须在券上架时就绪，否则秒杀开始瞬间会出现「库存未初始化」。
    // 三步顺序：先写券（拿 id）→ 再写配置（关联 id）→ 最后预热库存。
    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        // 第一张表：券的通用信息；MyBatis-Plus 回填自增 id 到 voucher
        // 后续的关联与预热都依赖这个 id
        save(voucher);
        // 保存秒杀信息
        // 从券实体上取秒杀配置，落到第二张表
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        // 关联字段：秒杀配置挂在哪张券上
        seckillVoucher.setVoucherId(voucher.getId());
        // 秒杀库存：与券实体上的库存字段一致
        // 两边各存一份：表里留档，Redis 里参与秒杀扣减
        seckillVoucher.setStock(voucher.getStock());
        // 秒杀开始时间：开始前前端按钮置灰
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        // 秒杀结束时间：结束后自动转为普通券形态展示
        // 下单链路会校验当前时间是否落在 [beginTime, endTime] 区间内
        seckillVoucher.setEndTime(voucher.getEndTime());
        // 第二张表落库；任意一步失败，事务回滚，两张表都不会留下脏数据
        // 注意：save 返回值这里不需要判断，异常会向上抛出触发回滚
        seckillVoucherService.save(seckillVoucher);
        // 保存秒杀库存到Redis中
        // key 形如 seckill:stock:券id，与下单扣减时使用同一规则
        // 不设 TTL：库存与券同生命周期，下架由业务显式处理
        // 写入字符串是因为 Redis 的 DECR 只对字符串数值生效
        // 预热失败不回滚数据库：可由补偿任务重试，两份数据最终一致
        // 重复新增同一张券会覆盖库存，运营侧需保证 id 唯一
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }
}
// —— 本类小结 ——
// queryVoucherOfShop：联表查询店铺的两种券，读路径简单；
// addSeckillVoucher：事务双表写入 + Redis 库存预热，为秒杀链路备好弹药。
// 普通券的新增走 save() 即可，不需要预热，只有秒杀券有库存预热动作。
// 注意：预热动作在事务方法内，若后续有异步化改造，需保证 Redis 写入在事务提交后执行。
// 更严谨的做法是用 TransactionSynchronization 在 afterCommit 里预热，
// 避免「事务回滚但 Redis 已写入」的幽灵库存。
