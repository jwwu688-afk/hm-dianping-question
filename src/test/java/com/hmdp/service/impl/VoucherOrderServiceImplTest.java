package com.hmdp.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.annotation.PreDestroy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoucherOrderServiceImplTest {

    @Test
    void createsOrderStreamAndConsumerGroupWhenApplicationStarts() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisStreamCommands commands = mock(RedisStreamCommands.class);
        when(connection.streamCommands()).thenReturn(commands);
        when(template.execute(any(RedisCallback.class))).thenAnswer(invocation ->
                ((RedisCallback<?>) invocation.getArgument(0)).doInRedis(connection));
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", template);

        service.initializeOrderStream();

        verify(commands).xGroupCreate(
                eq("stream.orders".getBytes(StandardCharsets.UTF_8)),
                eq("g1"), any(), eq(true));
    }

    @Test
    void allowsRestartWhenConsumerGroupAlreadyExists() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisCallback.class))).thenThrow(
                new RedisSystemException("BUSYGROUP Consumer Group name already exists", new RuntimeException()));
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", template);

        assertDoesNotThrow(service::initializeOrderStream);
    }

    @Test
    void doesNotHideOtherRedisInitializationFailures() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisCallback.class))).thenThrow(
                new DataAccessResourceFailureException("Redis unavailable"));
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", template);

        assertThrows(DataAccessResourceFailureException.class, service::initializeOrderStream);
    }

    @Test
    void shutsDownOwnedOrderExecutorWhenBeanIsDestroyed() throws Exception {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        Method shutdown = VoucherOrderServiceImpl.class.getDeclaredMethod("shutdownOrderExecutor");
        shutdown.setAccessible(true);

        Field executorField = VoucherOrderServiceImpl.class.getDeclaredField("seckillOrderExecutor");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(service);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        executor.submit(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        shutdown.invoke(service);

        assertTrue(shutdown.isAnnotationPresent(PreDestroy.class));
        assertFalse(Modifier.isStatic(executorField.getModifiers()));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertTrue(executor.isShutdown());
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    }
}
