package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.OrderRabbitConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.*;
import com.hmdp.mapper.*;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.Arrays;
import static com.hmdp.utils.RedisConstants.*;

/**
 * 秒杀订单链路：HTTP → Redis Lua 预扣库存 → 直接发送 RabbitMQ → MySQL 落库。
 *
 * <p>这里只保留教学所需的主流程：Lua 判断 Redis 库存和一人一单，成功后预扣库存，
 * Java 直接把 VoucherOrder 发送到 RabbitMQ，消费者收到后扣减 MySQL 库存并保存订单。</p>
 *
 * <p>按原项目结构，入口、RabbitMQ 发送和消费都保留在本类，不新增 messaging 包。
 * 文件末尾原 Redis Stream 代码已全部注释，不会创建消费者线程或参与运行。</p>
 *
 * <p>按当前要求，不处理生产者确认、消费重试、死信、分布式锁、幂等和跨存储补偿。</p>
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {
    // 提前声明脚本位置和返回类型；Redis 执行期间，库存检查与相关写入不会被其他命令穿插。
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = new DefaultRedisScript<>();
    static {
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    // Redis 保存秒杀库存和已购买用户集合；ID 生成器沿用原项目的全局订单号生成方式。
    private final StringRedisTemplate redis;
    private final RedisIdWorker ids;
    // RabbitTemplate 负责把 Lua 预扣成功的订单直接发送到 RabbitMQ。
    private final RabbitTemplate rabbit;
    private final SeckillVoucherMapper vouchers;

    /**
     * 构造器只注入完成主流程需要的 Redis、RabbitMQ 和数据库依赖。
     * orders 赋给父类 baseMapper，使原 IService CRUD 与消息消费使用同一个订单 Mapper。
     */
    public VoucherOrderServiceImpl(StringRedisTemplate redis, RedisIdWorker ids, RabbitTemplate rabbit,
            VoucherOrderMapper orders, SeckillVoucherMapper vouchers) {
        this.redis = redis; this.ids = ids;
        this.rabbit = rabbit; this.baseMapper = orders; this.vouchers = vouchers;
    }

    /**
     * 【替代原 Stream 秒杀入口】校验身份、生成订单 ID、执行 Redis 预扣并直接发送 RabbitMQ。
     * 返回成功表示消息发送调用已经完成，不表示消费者已经完成 MySQL 写入。
     * 当前脚本只检查库存和一人一单，不检查活动开始/结束时间。
     */
    @Override public Result seckillVoucher(Long voucherId) {
        // 拦截器把当前用户放入 ThreadLocal；异步消费不依赖该上下文，用户 ID 会写进消息。
        if (UserHolder.getUser() == null) return Result.fail("请先登录");
        if (voucherId == null || voucherId <= 0) return Result.fail("优惠券不存在");
        long userId = UserHolder.getUser().getId();
        long orderId = ids.nextId("order");  // 生成订单 ID
        VoucherOrder order = new VoucherOrder().setId(orderId).setUserId(userId).setVoucherId(voucherId);
        // Lua 只负责库存与一人一单校验；成功后由当前请求直接发送 RabbitMQ。
        Long result = redis.execute(SECKILL_SCRIPT, Arrays.asList(SECKILL_STOCK_KEY + voucherId,
                SECKILL_ORDER_KEY + voucherId), voucherId.toString(), Long.toString(userId), Long.toString(orderId));
        // 返回码：0 受理成功；1 无库存；2 重复购买；3 库存未初始化；4 数据/类型异常。
        // null 或未知返回值也按受理失败处理，不能向客户端报告成功。
        if (Long.valueOf(0).equals(result)) {
            // 最简单的发送方式：不等待 confirm，不使用额外消息 DTO，也不做失败补偿。
            // Fanout 交换机只关心绑定关系，不使用 routing key，因此第二个参数传空字符串。
            rabbit.convertAndSend(OrderRabbitConfig.EXCHANGE, "", order);
            return Result.ok(orderId);
        }
        if (Long.valueOf(1).equals(result)) return Result.fail("库存不足");
        if (Long.valueOf(2).equals(result)) return Result.fail("不能重复下单");
        if (Long.valueOf(3).equals(result)) return Result.fail("秒杀库存未初始化");
        return Result.fail("订单暂时无法受理，请稍后再试");
    }

    /**
     * 【替代 XREADGROUP/pending-list】监听 RabbitMQ 主队列，收到订单后直接消费。
     */
    @RabbitListener(queues = OrderRabbitConfig.QUEUE)
    public void onMessage(VoucherOrder order) {
        createVoucherOrder(order);
    }

    /**
     * 最简单的消费逻辑：扣减 MySQL 库存，然后插入订单。
     * 按当前要求，不增加锁、幂等判断、事务补偿和重试处理。
     */
    public void createVoucherOrder(VoucherOrder order) {
        vouchers.update(null, new UpdateWrapper<SeckillVoucher>()
                .setSql("stock = stock - 1").eq("voucher_id", order.getVoucherId()));
        baseMapper.insert(order);
    }
}

// ==================== 原 Redis Stream 逻辑（已停用，仅供对照） ====================
// package com.hmdp.service.impl;
//
// import cn.hutool.core.bean.BeanUtil;
// import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
// import com.hmdp.dto.Result;
// import com.hmdp.entity.VoucherOrder;
// import com.hmdp.mapper.VoucherOrderMapper;
// import com.hmdp.service.ISeckillVoucherService;
// import com.hmdp.service.IVoucherOrderService;
// import com.hmdp.utils.RedisIdWorker;
// import com.hmdp.utils.UserHolder;
// import lombok.extern.slf4j.Slf4j;
// import org.redisson.api.RLock;
// import org.redisson.api.RedissonClient;
// import org.springframework.core.io.ClassPathResource;
// import org.springframework.dao.DataAccessException;
// import org.springframework.data.redis.connection.stream.*;
// import org.springframework.data.redis.core.RedisCallback;
// import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.data.redis.core.script.DefaultRedisScript;
// import org.springframework.stereotype.Service;
//
// import javax.annotation.PostConstruct;
// import javax.annotation.PreDestroy;
// import javax.annotation.Resource;
// import java.nio.charset.StandardCharsets;
// import java.time.Duration;
// import java.util.Collections;
// import java.util.List;
// import java.util.Map;
// import java.util.concurrent.ExecutorService;
// import java.util.concurrent.Executors;
// import java.util.concurrent.Future;
// import java.util.concurrent.TimeUnit;
// import java.util.concurrent.atomic.AtomicBoolean;
//
// /**
//  * <p>
//  * 服务实现类
//  * </p>
//  *
//  * @author jarvis
//  * @since 2026-09-16
//  */
// @Slf4j
// @Service
// public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
//
//     @Resource
//     private ISeckillVoucherService seckillVoucherService;
//
//     @Resource
//     private RedisIdWorker redisIdWorker;
//     @Resource
//     private RedissonClient redissonClient;
//     @Resource
//     private StringRedisTemplate stringRedisTemplate;
//
//     private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
//
//     static {
//         SECKILL_SCRIPT = new DefaultRedisScript<>();
//         SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
//         SECKILL_SCRIPT.setResultType(Long.class);
//     }
//
//
//     private final ExecutorService seckillOrderExecutor = Executors.newSingleThreadExecutor();
//     private final AtomicBoolean orderHandlerRunning = new AtomicBoolean(false);
//     private volatile Future<?> orderHandlerTask;
//     private static final String ORDER_STREAM = "stream.orders";
//     private static final String ORDER_GROUP = "g1";
//
//     /**
//      * 初始化方法，在Bean属性设置完成后执行
//      * 使用@PostConstruct注解标记，表示该方法在依赖注入完成后自动执行
//      * 主要用于初始化订单处理相关的资源和状态
//      */
//     @PostConstruct
//     private void init() {
//         // 初始化订单流
//         initializeOrderStream();
//         // 设置订单处理器运行状态为true，表示开始处理订单
//         orderHandlerRunning.set(true);
//         // 向秒杀订单线程池提交一个订单处理任务，创建VoucherOrderHandler实例并执行
//         orderHandlerTask = seckillOrderExecutor.submit(new VoucherOrderHandler());
//     }
//
//     @PreDestroy
//     void shutdownOrderExecutor() {
//         orderHandlerRunning.set(false);
//         Future<?> task = orderHandlerTask;
//         if (task != null) {
//             task.cancel(true);
//         }
//         seckillOrderExecutor.shutdownNow();
//         try {
//             if (!seckillOrderExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
//                 log.warn("秒杀订单线程未在超时时间内结束");
//             }
//         } catch (InterruptedException exception) {
//             Thread.currentThread().interrupt();
//         }
//     }
//
//     /**
//      * 检查订单处理器是否正在运行
//      * 通过检查原子布尔变量和当前线程的中断状态来确定
//      *
//      * @return 如果订单处理器正在运行且当前线程未被中断，则返回true；否则返回false
//      */
//     private boolean isOrderHandlerRunning() {
//         // 获取orderHandlerRunning的当前值，并检查当前线程是否被中断
//         // 只有当orderHandlerRunning为true且当前线程未被中断时，才返回true
//         return orderHandlerRunning.get() && !Thread.currentThread().isInterrupted();
//     }
//
//     /**
//      * 初始化订单流，创建Redis Stream消费者组
//      * 如果消费者组已存在则忽略，其他异常则抛出
//      */
//     void initializeOrderStream() {
//         try {
//         // 使用Redis执行回调，创建Stream消费者组
//             stringRedisTemplate.execute((RedisCallback<String>) connection ->
//                 // 获取Stream命令并创建消费者组
//                     connection.streamCommands().xGroupCreate(
//                         // Stream的键名，使用UTF-8编码
//                             ORDER_STREAM.getBytes(StandardCharsets.UTF_8),
//                         // 消费者组名称
//                             ORDER_GROUP,
//                         // 从起始位置("0-0")开始读取消息
//                             ReadOffset.from("0-0"),
//                         // 如果已存在是否创建，true表示不存在才创建
//                             true
//                     ));
//         } catch (DataAccessException exception) {
//         // 检查异常是否为"BUSYGROUP"(消费者组已存在)
//             if (!hasMessage(exception, "BUSYGROUP")) {
//             // 如果不是消费者组已存在的异常，则抛出异常
//                 throw exception;
//             }
//         }
//     }
//
//     private boolean hasMessage(Throwable throwable, String text) {
//         for (Throwable current = throwable; current != null; current = current.getCause()) {
//             if (current.getMessage() != null && current.getMessage().contains(text)) {
//                 return true;
//             }
//         }
//         return false;
//     }
//
//     private class VoucherOrderHandler implements Runnable {
//
//         @Override
//         public void run() {
//             while (isOrderHandlerRunning()) {
//                 try {
//                     // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
//                     List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                             Consumer.from(ORDER_GROUP, "c1"),
//                             StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                             StreamOffset.create(ORDER_STREAM, ReadOffset.lastConsumed())
//                     );
//                     // 2.判断订单信息是否为空
//                     if (list == null || list.isEmpty()) {
//                         // 如果为null，说明没有消息，继续下一次循环
//                         continue;
//                     }
//                     // 解析数据
//                     MapRecord<String, Object, Object> record = list.get(0);
//                     Map<Object, Object> value = record.getValue();
//                     VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
//                     // 3.创建订单
//                     createVoucherOrder(voucherOrder);
//                     // 4.确认消息 XACK
//                     stringRedisTemplate.opsForStream().acknowledge(ORDER_STREAM, ORDER_GROUP, record.getId());
//                 } catch (Exception e) {
//                     if (!isOrderHandlerRunning()) {
//                         return;
//                     }
//                     log.error("处理订单异常", e);
//                     handlePendingList();
//                 }
//             }
//         }
//
//         private void handlePendingList() {
//             while (isOrderHandlerRunning()) {
//                 try {
//                     // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
//                     List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                             Consumer.from(ORDER_GROUP, "c1"),
//                             StreamReadOptions.empty().count(1),
//                             StreamOffset.create(ORDER_STREAM, ReadOffset.from("0"))
//                     );
//                     // 2.判断订单信息是否为空
//                     if (list == null || list.isEmpty()) {
//                         // 如果为null，说明没有异常消息，结束循环
//                         break;
//                     }
//                     // 解析数据
//                     MapRecord<String, Object, Object> record = list.get(0);
//                     Map<Object, Object> value = record.getValue();
//                     VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
//                     // 3.创建订单
//                     createVoucherOrder(voucherOrder);
//                     // 4.确认消息 XACK
//                     stringRedisTemplate.opsForStream().acknowledge(ORDER_STREAM, ORDER_GROUP, record.getId());
//                 } catch (Exception e) {
//                     if (!isOrderHandlerRunning()) {
//                         return;
//                     }
//                     log.error("处理订单异常", e);
//                 }
//             }
//         }
//     }
//
//     private void createVoucherOrder(VoucherOrder voucherOrder) {
//         Long userId = voucherOrder.getUserId();
//         Long voucherId = voucherOrder.getVoucherId();
//         // 创建锁对象
//         RLock redisLock = redissonClient.getLock("lock:order:" + userId);
//         // 尝试获取锁
//         boolean isLock = redisLock.tryLock();
//         // 判断
//         if (!isLock) {
//             // 获取锁失败，直接返回失败或者重试
//             log.error("不允许重复下单！");
//             return;
//         }
//
//         try {
//             // 5.1.查询订单
//             int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//             // 5.2.判断是否存在
//             if (count > 0) {
//                 // 用户已经购买过了
//                 log.error("不允许重复下单！");
//                 return;
//             }
//
//             // 6.扣减库存
//             boolean success = seckillVoucherService.update()
//                     .setSql("stock = stock - 1") // set stock = stock - 1
//                     .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
//                     .update();
//             if (!success) {
//                 // 扣减失败
//                 log.error("库存不足！");
//                 return;
//             }
//
//             // 7.创建订单
//             save(voucherOrder);
//         } finally {
//             // 释放锁
//             redisLock.unlock();
//         }
//     }
//
//     @Override
//     public Result seckillVoucher(Long voucherId) {
//         // 获取用户 + 生成订单号
//         Long userId = UserHolder.getUser().getId();
//         long orderId = redisIdWorker.nextId("order");  // 用 Redis 自增生成全局唯一订单号
//         // 1.执行lua脚本
//         Long result = stringRedisTemplate.execute(
//                 SECKILL_SCRIPT,
//                 Collections.emptyList(),
//                 voucherId.toString(), userId.toString(), String.valueOf(orderId)
//         );
//         int r = result.intValue();
//         // 2.判断结果是否为0
//         if (r != 0) {
//             // 2.1.不为0 ，代表没有购买资格
//             return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//         }
//         // 3.返回订单id
//         return Result.ok(orderId);
//     }
// }
