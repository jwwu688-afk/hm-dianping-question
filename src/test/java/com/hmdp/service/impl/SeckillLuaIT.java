// 包声明：被测服务所在包（集成测试放服务实现包下）
package com.hmdp.service.impl;

// AfterEach：每个用例后执行
import org.junit.jupiter.api.AfterEach;
// BeforeEach：每个用例前执行
import org.junit.jupiter.api.BeforeEach;
// Test：测试方法注解
import org.junit.jupiter.api.Test;
// ClassPathResource：加载 classpath 下 seckill.lua
import org.springframework.core.io.ClassPathResource;
// LettuceConnectionFactory：Lettuce 连接工厂
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
// StringRedisTemplate：Redis 字符串模板
import org.springframework.data.redis.core.StringRedisTemplate;
// DefaultRedisScript：Lua 脚本执行器
import org.springframework.data.redis.core.script.DefaultRedisScript;
// GenericContainer：Testcontainers 通用容器
import org.testcontainers.containers.GenericContainer;
// Container：容器字段注解（自动启停）
import org.testcontainers.junit.jupiter.Container;
// Testcontainers：启用容器管理
import org.testcontainers.junit.jupiter.Testcontainers;

// Arrays：构造 keys 列表
import java.util.Arrays;

// assertEquals：相等断言
import static org.junit.jupiter.api.Assertions.assertEquals;
// assertFalse：假值断言
import static org.junit.jupiter.api.Assertions.assertFalse;
// assertTrue：真值断言
import static org.junit.jupiter.api.Assertions.assertTrue;

// ===== Testcontainers 隔离 Redis 的 Lua 行为验证 =====
/** 使用隔离 Redis 验证秒杀 Lua 只完成库存预扣和一人一单记录。 */
// 启用 Testcontainers 容器生命周期管理
@Testcontainers
// Lua 脚本行为验证类（隔离集成测试）
class SeckillLuaIT {
// 容器注解：测试类启动时拉起、结束时销毁
    @Container
// redis:7-alpine 容器，暴露 6379 端口（映射为随机宿主端口）
    static GenericContainer<?> redisServer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

// Lettuce 连接工厂（每个用例重建）
    LettuceConnectionFactory connection;
// Redis 字符串操作模板
    StringRedisTemplate redis;
// Lua 脚本执行器
    DefaultRedisScript<Long> script;

// 生命周期：每个用例前执行
    @BeforeEach
// setup 方法体
    void setup() {
// 连接容器映射出的随机宿主端口
        connection = new LettuceConnectionFactory(redisServer.getHost(), redisServer.getMappedPort(6379));
// 初始化连接工厂
        connection.afterPropertiesSet();
// 构建字符串模板
        redis = new StringRedisTemplate(connection);
// 回调式执行 FLUSHDB
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) commands -> {
// 清空当前 DB，保证用例间数据隔离
            commands.serverCommands().flushDb();
// 回调返回 null（无返回值）
            return null;
// 回调调用结束
        });
// 创建 Lua 脚本执行器
        script = new DefaultRedisScript<>();
// 指定 classpath 下的 seckill.lua
        script.setLocation(new ClassPathResource("seckill.lua"));
// 指定返回类型 Long
        script.setResultType(Long.class);
// setup 方法结束
    }

// 生命周期：每个用例后执行
    @AfterEach
// close 方法体
    void close() {
// 销毁连接工厂释放资源
        connection.destroy();
// close 方法结束
    }

// 私有工具：对券 7 执行一次 Lua 预扣
    private Long reserve(String userId, String orderId) {
// keys：库存 key + 一人一单集合 key
        return redis.execute(script, Arrays.asList("seckill:stock:7", "seckill:order:7"),
// argv：券 ID / 用户 ID / 订单 ID
                "7", userId, orderId);
// reserve 方法结束
    }

// 用例一：正常预扣应只更新库存并记录买家，不产生流/发件箱 key
    @Test
// 测试方法一
    void deductsStockAndRecordsPurchaserWithoutCreatingStreamOrOutbox() {
// 预置库存 2 件
        redis.opsForValue().set("seckill:stock:7", "2");

// 首次预扣返回 0 = 拿到资格
        assertEquals(0L, reserve("42", "1"));
// 库存被预扣到 1
        assertEquals("1", redis.opsForValue().get("seckill:stock:7"));
// 用户 42 已记入一人一单集合
        assertTrue(redis.opsForSet().isMember("seckill:order:7", "42"));
// 断言未创建 stream.orders 流 key（订单投递不在 Lua 职责内）
        assertFalse(redis.hasKey("stream.orders"));
// 断言未创建 seckill:outbox:payloads 发件箱 key
        assertFalse(redis.hasKey("seckill:outbox:payloads"));

// 同一用户第二次预扣返回 2 = 一人一单拒绝
        assertEquals(2L, reserve("42", "2"));
// 库存保持 1 不变（重复请求未扣减）
        assertEquals("1", redis.opsForValue().get("seckill:stock:7"));
// 用例一方法结束
    }

// 用例二：库存缺失或类型错误时应在写任何数据前被拒绝
    @Test
// 测试方法二
    void rejectsMissingStockAndWrongKeyTypeBeforeWriting() {
// 库存 key 不存在 → 返回 3（未初始化错误码）
        assertEquals(3L, reserve("42", "1"));
// 预置库存 2 件
        redis.opsForValue().set("seckill:stock:7", "2");
// 把一人一单 key 写成 string 类型（制造类型错误）
        redis.opsForValue().set("seckill:order:7", "wrong-type");

// 类型不匹配 → 返回 4（错误码），拒绝写入
        assertEquals(4L, reserve("42", "1"));
// 库存未被扣减，保持 2（写前校验生效）
        assertEquals("2", redis.opsForValue().get("seckill:stock:7"));
// 用例二方法结束
    }
// SeckillLuaIT 类结束
}
