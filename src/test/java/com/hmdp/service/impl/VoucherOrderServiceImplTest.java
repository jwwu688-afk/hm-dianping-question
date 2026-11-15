// 包声明：被测服务所在包（单元测试贴着实现放）
package com.hmdp.service.impl;

// OrderRabbitConfig：生产 MQ 拓扑常量（交换机名等）
import com.hmdp.config.OrderRabbitConfig;
// Result：统一返回结果 DTO
import com.hmdp.dto.Result;
// UserDTO：登录用户上下文对象
import com.hmdp.dto.UserDTO;
// VoucherOrder：订单实体
import com.hmdp.entity.VoucherOrder;
// SeckillVoucherMapper：秒杀券库存 Mapper（mock）
import com.hmdp.mapper.SeckillVoucherMapper;
// VoucherOrderMapper：订单 Mapper（mock）
import com.hmdp.mapper.VoucherOrderMapper;
// RedisIdWorker：基于 Redis 的全局 ID 生成器（mock）
import com.hmdp.utils.RedisIdWorker;
// UserHolder：用户上下文持有器（ThreadLocal）
import com.hmdp.utils.UserHolder;
// AfterEach：每个用例后执行
import org.junit.jupiter.api.AfterEach;
// Test：测试方法注解
import org.junit.jupiter.api.Test;
// ArgumentCaptor：捕获调用参数做断言
import org.mockito.ArgumentCaptor;
// RabbitTemplate：消息发送模板（mock）
import org.springframework.amqp.rabbit.core.RabbitTemplate;
// StringRedisTemplate：Redis 模板（mock）
import org.springframework.data.redis.core.StringRedisTemplate;
// RedisScript：Lua 脚本类型（用于 any 匹配）
import org.springframework.data.redis.core.script.RedisScript;

// Assertions：JUnit 断言工具（通配导入）
import static org.junit.jupiter.api.Assertions.*;
// ArgumentMatchers：Mockito 参数匹配器（通配导入）
import static org.mockito.ArgumentMatchers.*;
// Mockito：mock/when/verify 工具（通配导入）
import static org.mockito.Mockito.*;

// ===== 主流程单元测试：Lua 成功后发送订单、消费者保存订单 =====
/** 只验证“Lua 成功后发送订单”和“消费者保存订单”两段主流程。 */
// 服务层单元测试类（纯 Mockito，不起 Spring 容器）
class VoucherOrderServiceImplTest {
// 生命周期：每个用例后执行
    @AfterEach
// cleanup 方法体
    void cleanup() {
// 清理 ThreadLocal 用户，避免用例间污染
        UserHolder.removeUser();
// cleanup 方法结束
    }

// 用例一：Lua 预扣成功后应直接发布订单消息
    @Test
// 测试方法一
    void successfulReservationDirectlyPublishesVoucherOrder() {
// 构造测试用户
        UserDTO user = new UserDTO();
// 设置用户 ID 42
        user.setId(42L);
// 写入用户上下文（模拟已登录）
        UserHolder.saveUser(user);

// mock ID 生成器
        RedisIdWorker ids = mock(RedisIdWorker.class);
// 打桩：订单 ID 固定返回该值
        when(ids.nextId("order")).thenReturn(9007199254740993L);
// mock Redis 模板
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
// 打桩：Lua 脚本调用返回 0（预扣成功）
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
// 打桩续行
                .thenReturn(0L);
// mock 消息发送模板
        RabbitTemplate rabbit = mock(RabbitTemplate.class);

// 构造被测服务（全部依赖 mock）
        VoucherOrderServiceImpl service = service(redis, ids, rabbit,
// 构造续行：注入 mock 持久层
                mock(VoucherOrderMapper.class), mock(SeckillVoucherMapper.class));
// 调用秒杀接口
        Result result = service.seckillVoucher(7L);

// 验证 Lua 以正确的 keys/argv 被执行
        verify(redis).execute(any(RedisScript.class),
// keys 匹配：库存 key + 一人一单集合
                eq(java.util.Arrays.asList("seckill:stock:7", "seckill:order:7")),
// argv 匹配：券/用户/订单 ID
                eq("7"), eq("42"), eq("9007199254740993"));
// 参数捕获器：抓取发送出去的订单对象
        ArgumentCaptor<VoucherOrder> order = ArgumentCaptor.forClass(VoucherOrder.class);
        // Fanout 交换机不根据 routing key 路由，生产者统一传空字符串。
// 验证订单被发送到生产交换机，routing key 为空
        verify(rabbit).convertAndSend(eq(OrderRabbitConfig.EXCHANGE), eq(""), order.capture());
// 断言订单 ID = 打桩返回的 ID
        assertEquals(9007199254740993L, order.getValue().getId());
// 断言订单用户 ID = 42
        assertEquals(42L, order.getValue().getUserId());
// 断言订单券 ID = 7
        assertEquals(7L, order.getValue().getVoucherId());
// 断言接口返回成功
        assertTrue(result.getSuccess());
// 断言接口数据 = 订单 ID
        assertEquals(9007199254740993L, result.getData());
// 用例一方法结束
    }

// 用例二：Lua 预扣失败（返回 1）不应发送任何 MQ 消息
    @Test
// 测试方法二
    void luaFailureDoesNotSendRabbitMessage() {
// 构造测试用户
        UserDTO user = new UserDTO();
// 设置用户 ID 42
        user.setId(42L);
// 写入用户上下文
        UserHolder.saveUser(user);
// mock Redis 模板
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
// 打桩：Lua 返回 1 = 预扣失败（库存不足或重复下单）
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString())).thenReturn(1L);
// mock 消息发送模板
        RabbitTemplate rabbit = mock(RabbitTemplate.class);

// 构造服务并直接调用秒杀接口
        Result result = service(redis, mock(RedisIdWorker.class), rabbit,
// 调用续行
                mock(VoucherOrderMapper.class), mock(SeckillVoucherMapper.class)).seckillVoucher(7L);
// 断言接口返回失败

// 验证 rabbit 完全未被调用（失败不投递消息）
        assertFalse(result.getSuccess());
// 用例二方法结束
        verifyNoInteractions(rabbit);
    }

// 用例三：消费端应扣减数据库库存并插入订单
    @Test
// 测试方法三
    void consumerDeductsDatabaseStockAndInsertsOrder() {
// mock 订单 Mapper
        VoucherOrderMapper orders = mock(VoucherOrderMapper.class);
// mock 库存 Mapper
        SeckillVoucherMapper vouchers = mock(SeckillVoucherMapper.class);
// 链式构造一条订单消息体
        VoucherOrder order = new VoucherOrder().setId(1L).setUserId(42L).setVoucherId(7L);

// 构造服务并直接调用消费入口 onMessage
        service(mock(StringRedisTemplate.class), mock(RedisIdWorker.class), mock(RabbitTemplate.class),
// 调用续行
                orders, vouchers).onMessage(order);

// 验证扣减库存被调用（实体为空即无条件扣减语义）
        verify(vouchers).update(isNull(), any());
// 验证订单插入被调用
        verify(orders).insert(order);
// 用例三方法结束
    }

// 私有工厂方法：统一构造被测服务
    private VoucherOrderServiceImpl service(StringRedisTemplate redis, RedisIdWorker ids, RabbitTemplate rabbit,
// 签名续行
            VoucherOrderMapper orders, SeckillVoucherMapper vouchers) {
// 返回注入 mock 依赖的服务实例
        return new VoucherOrderServiceImpl(redis, ids, rabbit, orders, vouchers);
// 工厂方法结束
    }
// VoucherOrderServiceImplTest 类结束
}
