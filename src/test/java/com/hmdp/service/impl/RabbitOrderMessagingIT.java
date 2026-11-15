// 包声明：被测服务所在包（集成测试放服务实现包下）
package com.hmdp.service.impl;

// OrderRabbitConfig：生产 MQ 拓扑配置（交换机/队列/绑定/转换器）
import com.hmdp.config.OrderRabbitConfig;
// Result：统一返回结果 DTO
import com.hmdp.dto.Result;
// UserDTO：登录用户上下文对象
import com.hmdp.dto.UserDTO;
// VoucherOrder：订单实体（消息体）
import com.hmdp.entity.VoucherOrder;
// SeckillVoucherMapper：秒杀券库存 Mapper（mock）
import com.hmdp.mapper.SeckillVoucherMapper;
// VoucherOrderMapper：订单 Mapper（mock，验证落库调用）
import com.hmdp.mapper.VoucherOrderMapper;
// RedisIdWorker：基于 Redis 的全局 ID 生成器
import com.hmdp.utils.RedisIdWorker;
// UserHolder：用户上下文持有器（ThreadLocal）
import com.hmdp.utils.UserHolder;
// AfterEach：每个用例后执行
import org.junit.jupiter.api.AfterEach;
// BeforeEach：每个用例前执行
import org.junit.jupiter.api.BeforeEach;
// Test：测试方法注解
import org.junit.jupiter.api.Test;
// CachingConnectionFactory：带连接缓存的 RabbitMQ 连接工厂
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
// RabbitAdmin：MQ 拓扑管理（声明交换机/队列/绑定）
import org.springframework.amqp.rabbit.core.RabbitAdmin;
// RabbitTemplate：消息发送模板
import org.springframework.amqp.rabbit.core.RabbitTemplate;
// SimpleMessageListenerContainer：消息监听容器（消费者）
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
// MessageListenerAdapter：监听器适配器（反射调用 onMessage）
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
// LettuceConnectionFactory：Redis 连接工厂
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
// StringRedisTemplate：Redis 字符串模板
import org.springframework.data.redis.core.StringRedisTemplate;
// GenericContainer：Testcontainers 通用容器
import org.testcontainers.containers.GenericContainer;
// Container：容器字段注解（自动启停）
import org.testcontainers.junit.jupiter.Container;
// Testcontainers：启用容器管理
import org.testcontainers.junit.jupiter.Testcontainers;

// Duration：等待超时时长
import java.time.Duration;

// await：Awaitility 轮询断言（异步结果等待）
import static org.awaitility.Awaitility.await;
// assertEquals：相等断言
import static org.junit.jupiter.api.Assertions.assertEquals;
// assertTrue：真值断言
import static org.junit.jupiter.api.Assertions.assertTrue;
// ArgumentMatchers：Mockito 参数匹配器
import static org.mockito.ArgumentMatchers.*;
// Mockito：mock 与 verify 工具
import static org.mockito.Mockito.*;

// ===== Testcontainers 隔离 Redis + RabbitMQ 的主流程验证 =====
/** 使用隔离 Redis 和 RabbitMQ 验证最简单的“Lua → 发送 → 消费”主流程。 */
// 启用 Testcontainers 容器生命周期管理
@Testcontainers
// Lua → 发送 → 消费 主流程集成测试类
class RabbitOrderMessagingIT {
// 容器注解：类级共享，自动启停
    @Container
// rabbitmq:3.8-management 容器
    static GenericContainer<?> rabbit = new GenericContainer<>("rabbitmq:3.8-management")
// 设置默认用户名
            .withEnv("RABBITMQ_DEFAULT_USER", "mqtest")
// 设置默认密码
            .withEnv("RABBITMQ_DEFAULT_PASS", "mqtest")
// 暴露 AMQP 5672 端口
            .withExposedPorts(5672)
// 等待日志出现 Server startup complete 才继续
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Server startup complete.*", 1));

// 容器注解：Redis 容器
    @Container
// redis:7-alpine 容器，暴露 6379 端口
    static GenericContainer<?> redisServer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

// MQ 连接工厂
    CachingConnectionFactory broker;
// Redis 连接工厂
    LettuceConnectionFactory redisConnection;
// 消息监听容器
    SimpleMessageListenerContainer listener;
// Redis 字符串模板
    StringRedisTemplate redis;
// 订单 Mapper（mock）
    VoucherOrderMapper orders;
// 秒杀券库存 Mapper（mock）
    SeckillVoucherMapper vouchers;
// 被测服务实例（真实逻辑 + mock 依赖）
    VoucherOrderServiceImpl service;

// 生命周期：每个用例前执行
    @BeforeEach
// connect 方法体：搭建完整 MQ/Redis 环境
    void connect() {
// 加载生产 MQ 拓扑配置类
        OrderRabbitConfig topology = new OrderRabbitConfig();
// 连接 Rabbit 容器映射端口
        broker = new CachingConnectionFactory(rabbit.getHost(), rabbit.getMappedPort(5672));
// 设置 MQ 用户名
        broker.setUsername("mqtest");
// 设置 MQ 密码
        broker.setPassword("mqtest");

// 创建 RabbitAdmin 用于声明拓扑
        RabbitAdmin admin = new RabbitAdmin(broker);
// 声明生产同款订单交换机
        admin.declareExchange(topology.orderExchange());
// 声明生产同款订单队列
        admin.declareQueue(topology.orderQueue());
// 声明队列与交换机的绑定
        admin.declareBinding(topology.orderBinding());
// 清空队列残留消息，保证用例起点干净
        admin.purgeQueue(OrderRabbitConfig.QUEUE, false);

// 创建发送模板
        RabbitTemplate template = new RabbitTemplate(broker);
// 使用生产同款 JSON 消息转换器
        template.setMessageConverter(topology.orderMessageConverter());

// 连接 Redis 容器映射端口
        redisConnection = new LettuceConnectionFactory(redisServer.getHost(), redisServer.getMappedPort(6379));
// 初始化 Redis 连接工厂
        redisConnection.afterPropertiesSet();
// 构建字符串模板
        redis = new StringRedisTemplate(redisConnection);
// 回调式执行 FLUSHDB
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) commands -> {
// 清空 Redis 数据，保证用例隔离
            commands.serverCommands().flushDb();
// 回调返回 null
            return null;
// 回调调用结束
        });

// mock 订单 Mapper（验证 insert 调用）
        orders = mock(VoucherOrderMapper.class);
// mock 秒杀券库存 Mapper（验证扣库存调用）
        vouchers = mock(SeckillVoucherMapper.class);
// 构造被测服务：真实业务逻辑 + mock 持久层
        service = new VoucherOrderServiceImpl(redis, new RedisIdWorker(redis), template, orders, vouchers);

// 监听器适配器：反射调用服务的 onMessage 方法
        MessageListenerAdapter adapter = new MessageListenerAdapter(service, "onMessage");
// 适配器使用与发送端一致的 JSON 转换器
        adapter.setMessageConverter(topology.orderMessageConverter());
// 创建监听容器
        listener = new SimpleMessageListenerContainer(broker);
// 订阅订单队列
        listener.setQueueNames(OrderRabbitConfig.QUEUE);
// 挂载监听适配器
        listener.setMessageListener(adapter);
// 启动消费者容器
        listener.start();
// connect 方法结束
    }

// 生命周期：每个用例后执行
    @AfterEach
// close 方法体：清理用户上下文并停容器
    void close() {
// 清理 ThreadLocal 用户，避免污染其他用例
        UserHolder.removeUser();
// 停止监听容器
        if (listener != null) listener.stop();
// 销毁 Redis 连接工厂
        if (redisConnection != null) redisConnection.destroy();
// 销毁 MQ 连接工厂
        if (broker != null) broker.destroy();
// close 方法结束
    }

// 用例：Lua 预扣成功 → 发送订单消息 → 消费端处理落库
    @Test
// 主流程测试方法
    void luaSuccessSendsOrderAndConsumerProcessesIt() {
// 预置券 7 库存 10 件
        redis.opsForValue().set("seckill:stock:7", "10");
// 构造测试用户
        UserDTO user = new UserDTO();
// 设置用户 ID 42
        user.setId(42L);
// 写入用户上下文（模拟已登录）
        UserHolder.saveUser(user);

// 调用秒杀接口
        Result result = service.seckillVoucher(7L);

// 断言接口返回成功
        assertTrue(result.getSuccess());
// 断言 Redis 库存被预扣到 9
        assertEquals("9", redis.opsForValue().get("seckill:stock:7"));
// Awaitility 等待异步消费完成（最长 10 秒）
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
// 验证消费端扣减了 MySQL 库存
            verify(vouchers).update(isNull(), any());
// 验证消费端插入了订单，且字段匹配
            verify(orders).insert(argThat(order -> order instanceof VoucherOrder
// 订单 ID 与接口返回一致
                    && order.getId().equals(result.getData())
// 用户 ID 为 42
                    && order.getUserId().equals(42L)
// 券 ID 为 7
                    && order.getVoucherId().equals(7L)));
// 轮询断言结束
        });
// 用例方法结束
    }
// RabbitOrderMessagingIT 类结束
}
