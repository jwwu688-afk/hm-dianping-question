package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author jarvis
 * @since 2026-09-16
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private final ExecutorService seckillOrderExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean orderHandlerRunning = new AtomicBoolean(false);
    private volatile Future<?> orderHandlerTask;
    private static final String ORDER_STREAM = "stream.orders";
    private static final String ORDER_GROUP = "g1";

    /**
     * 初始化方法，在Bean属性设置完成后执行
     * 使用@PostConstruct注解标记，表示该方法在依赖注入完成后自动执行
     * 主要用于初始化订单处理相关的资源和状态
     */
    @PostConstruct
    private void init() {
        // 初始化订单流
        initializeOrderStream();
        // 设置订单处理器运行状态为true，表示开始处理订单
        orderHandlerRunning.set(true);
        // 向秒杀订单线程池提交一个订单处理任务，创建VoucherOrderHandler实例并执行
        orderHandlerTask = seckillOrderExecutor.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    void shutdownOrderExecutor() {
        orderHandlerRunning.set(false);
        Future<?> task = orderHandlerTask;
        if (task != null) {
            task.cancel(true);
        }
        seckillOrderExecutor.shutdownNow();
        try {
            if (!seckillOrderExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("秒杀订单线程未在超时时间内结束");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 检查订单处理器是否正在运行
     * 通过检查原子布尔变量和当前线程的中断状态来确定
     *
     * @return 如果订单处理器正在运行且当前线程未被中断，则返回true；否则返回false
     */
    private boolean isOrderHandlerRunning() {
        // 获取orderHandlerRunning的当前值，并检查当前线程是否被中断
        // 只有当orderHandlerRunning为true且当前线程未被中断时，才返回true
        return orderHandlerRunning.get() && !Thread.currentThread().isInterrupted();
    }

    /**
     * 初始化订单流，创建Redis Stream消费者组
     * 如果消费者组已存在则忽略，其他异常则抛出
     */
    void initializeOrderStream() {
        try {
        // 使用Redis执行回调，创建Stream消费者组
            stringRedisTemplate.execute((RedisCallback<String>) connection ->
                // 获取Stream命令并创建消费者组
                    connection.streamCommands().xGroupCreate(
                        // Stream的键名，使用UTF-8编码
                            ORDER_STREAM.getBytes(StandardCharsets.UTF_8),
                        // 消费者组名称
                            ORDER_GROUP,
                        // 从起始位置("0-0")开始读取消息
                            ReadOffset.from("0-0"),
                        // 如果已存在是否创建，true表示不存在才创建
                            true
                    ));
        } catch (DataAccessException exception) {
        // 检查异常是否为"BUSYGROUP"(消费者组已存在)
            if (!hasMessage(exception, "BUSYGROUP")) {
            // 如果不是消费者组已存在的异常，则抛出异常
                throw exception;
            }
        }
    }

    private boolean hasMessage(Throwable throwable, String text) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (isOrderHandlerRunning()) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_GROUP, "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(ORDER_STREAM, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(ORDER_STREAM, ORDER_GROUP, record.getId());
                } catch (Exception e) {
                    if (!isOrderHandlerRunning()) {
                        return;
                    }
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (isOrderHandlerRunning()) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_GROUP, "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(ORDER_STREAM, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(ORDER_STREAM, ORDER_GROUP, record.getId());
                } catch (Exception e) {
                    if (!isOrderHandlerRunning()) {
                        return;
                    }
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                log.error("不允许重复下单！");
                return;
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }

            // 7.创建订单
            save(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户 + 生成订单号
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");  // 用 Redis 自增生成全局唯一订单号
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.返回订单id
        return Result.ok(orderId);
    }
}
