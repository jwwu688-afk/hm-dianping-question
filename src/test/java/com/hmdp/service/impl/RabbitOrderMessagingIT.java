package com.hmdp.service.impl;

import com.hmdp.config.OrderRabbitConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用隔离 Redis 和 RabbitMQ 验证最简单的“Lua → 发送 → 消费”主流程。 */
@Testcontainers
class RabbitOrderMessagingIT {
    @Container
    static GenericContainer<?> rabbit = new GenericContainer<>("rabbitmq:3.8-management")
            .withEnv("RABBITMQ_DEFAULT_USER", "mqtest")
            .withEnv("RABBITMQ_DEFAULT_PASS", "mqtest")
            .withExposedPorts(5672)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Server startup complete.*", 1));

    @Container
    static GenericContainer<?> redisServer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    CachingConnectionFactory broker;
    LettuceConnectionFactory redisConnection;
    SimpleMessageListenerContainer listener;
    StringRedisTemplate redis;
    VoucherOrderMapper orders;
    SeckillVoucherMapper vouchers;
    VoucherOrderServiceImpl service;

    @BeforeEach
    void connect() {
        OrderRabbitConfig topology = new OrderRabbitConfig();
        broker = new CachingConnectionFactory(rabbit.getHost(), rabbit.getMappedPort(5672));
        broker.setUsername("mqtest");
        broker.setPassword("mqtest");

        RabbitAdmin admin = new RabbitAdmin(broker);
        admin.declareExchange(topology.orderExchange());
        admin.declareQueue(topology.orderQueue());
        admin.declareBinding(topology.orderBinding());
        admin.purgeQueue(OrderRabbitConfig.QUEUE, false);

        RabbitTemplate template = new RabbitTemplate(broker);
        template.setMessageConverter(topology.orderMessageConverter());

        redisConnection = new LettuceConnectionFactory(redisServer.getHost(), redisServer.getMappedPort(6379));
        redisConnection.afterPropertiesSet();
        redis = new StringRedisTemplate(redisConnection);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) commands -> {
            commands.serverCommands().flushDb();
            return null;
        });

        orders = mock(VoucherOrderMapper.class);
        vouchers = mock(SeckillVoucherMapper.class);
        service = new VoucherOrderServiceImpl(redis, new RedisIdWorker(redis), template, orders, vouchers);

        MessageListenerAdapter adapter = new MessageListenerAdapter(service, "onMessage");
        adapter.setMessageConverter(topology.orderMessageConverter());
        listener = new SimpleMessageListenerContainer(broker);
        listener.setQueueNames(OrderRabbitConfig.QUEUE);
        listener.setMessageListener(adapter);
        listener.start();
    }

    @AfterEach
    void close() {
        UserHolder.removeUser();
        if (listener != null) listener.stop();
        if (redisConnection != null) redisConnection.destroy();
        if (broker != null) broker.destroy();
    }

    @Test
    void luaSuccessSendsOrderAndConsumerProcessesIt() {
        redis.opsForValue().set("seckill:stock:7", "10");
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);

        Result result = service.seckillVoucher(7L);

        assertTrue(result.getSuccess());
        assertEquals("9", redis.opsForValue().get("seckill:stock:7"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            verify(vouchers).update(isNull(), any());
            verify(orders).insert(argThat(order -> order instanceof VoucherOrder
                    && order.getId().equals(result.getData())
                    && order.getUserId().equals(42L)
                    && order.getVoucherId().equals(7L)));
        });
    }
}
