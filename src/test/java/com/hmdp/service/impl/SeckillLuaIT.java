package com.hmdp.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用隔离 Redis 验证秒杀 Lua 只完成库存预扣和一人一单记录。 */
@Testcontainers
class SeckillLuaIT {
    @Container
    static GenericContainer<?> redisServer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    DefaultRedisScript<Long> script;

    @BeforeEach
    void setup() {
        connection = new LettuceConnectionFactory(redisServer.getHost(), redisServer.getMappedPort(6379));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) commands -> {
            commands.serverCommands().flushDb();
            return null;
        });
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
    }

    @AfterEach
    void close() {
        connection.destroy();
    }

    private Long reserve(String userId, String orderId) {
        return redis.execute(script, Arrays.asList("seckill:stock:7", "seckill:order:7"),
                "7", userId, orderId);
    }

    @Test
    void deductsStockAndRecordsPurchaserWithoutCreatingStreamOrOutbox() {
        redis.opsForValue().set("seckill:stock:7", "2");

        assertEquals(0L, reserve("42", "1"));
        assertEquals("1", redis.opsForValue().get("seckill:stock:7"));
        assertTrue(redis.opsForSet().isMember("seckill:order:7", "42"));
        assertFalse(redis.hasKey("stream.orders"));
        assertFalse(redis.hasKey("seckill:outbox:payloads"));

        assertEquals(2L, reserve("42", "2"));
        assertEquals("1", redis.opsForValue().get("seckill:stock:7"));
    }

    @Test
    void rejectsMissingStockAndWrongKeyTypeBeforeWriting() {
        assertEquals(3L, reserve("42", "1"));
        redis.opsForValue().set("seckill:stock:7", "2");
        redis.opsForValue().set("seckill:order:7", "wrong-type");

        assertEquals(4L, reserve("42", "1"));
        assertEquals("2", redis.opsForValue().get("seckill:stock:7"));
    }
}
