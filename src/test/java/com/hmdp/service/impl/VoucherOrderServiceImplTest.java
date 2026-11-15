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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只验证“Lua 成功后发送订单”和“消费者保存订单”两段主流程。 */
class VoucherOrderServiceImplTest {
    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void successfulReservationDirectlyPublishesVoucherOrder() {
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);

        RedisIdWorker ids = mock(RedisIdWorker.class);
        when(ids.nextId("order")).thenReturn(9007199254740993L);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);

        VoucherOrderServiceImpl service = service(redis, ids, rabbit,
                mock(VoucherOrderMapper.class), mock(SeckillVoucherMapper.class));
        Result result = service.seckillVoucher(7L);

        verify(redis).execute(any(RedisScript.class),
                eq(java.util.Arrays.asList("seckill:stock:7", "seckill:order:7")),
                eq("7"), eq("42"), eq("9007199254740993"));
        ArgumentCaptor<VoucherOrder> order = ArgumentCaptor.forClass(VoucherOrder.class);
        // Fanout 交换机不根据 routing key 路由，生产者统一传空字符串。
        verify(rabbit).convertAndSend(eq(OrderRabbitConfig.EXCHANGE), eq(""), order.capture());
        assertEquals(9007199254740993L, order.getValue().getId());
        assertEquals(42L, order.getValue().getUserId());
        assertEquals(7L, order.getValue().getVoucherId());
        assertTrue(result.getSuccess());
        assertEquals(9007199254740993L, result.getData());
    }

    @Test
    void luaFailureDoesNotSendRabbitMessage() {
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString())).thenReturn(1L);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);

        Result result = service(redis, mock(RedisIdWorker.class), rabbit,
                mock(VoucherOrderMapper.class), mock(SeckillVoucherMapper.class)).seckillVoucher(7L);

        assertFalse(result.getSuccess());
        verifyNoInteractions(rabbit);
    }

    @Test
    void consumerDeductsDatabaseStockAndInsertsOrder() {
        VoucherOrderMapper orders = mock(VoucherOrderMapper.class);
        SeckillVoucherMapper vouchers = mock(SeckillVoucherMapper.class);
        VoucherOrder order = new VoucherOrder().setId(1L).setUserId(42L).setVoucherId(7L);

        service(mock(StringRedisTemplate.class), mock(RedisIdWorker.class), mock(RabbitTemplate.class),
                orders, vouchers).onMessage(order);

        verify(vouchers).update(isNull(), any());
        verify(orders).insert(order);
    }

    private VoucherOrderServiceImpl service(StringRedisTemplate redis, RedisIdWorker ids, RabbitTemplate rabbit,
            VoucherOrderMapper orders, SeckillVoucherMapper vouchers) {
        return new VoucherOrderServiceImpl(redis, ids, rabbit, orders, vouchers);
    }
}
