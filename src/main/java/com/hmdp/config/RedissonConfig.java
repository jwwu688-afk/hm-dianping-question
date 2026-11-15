package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 创建项目原有功能可使用的 Redisson 客户端。
 * 地址与可选密码来自 spring.redis，避免 RedisTemplate 与分布式锁连接到不同服务器。
 * 当前简化后的 RabbitMQ 订单流程不注入、也不使用该客户端。
 */
// ==================== Redisson 客户端配置 ====================
// Redisson 在 RedisTemplate 之上提供更高层的分布式原语：
// RLock（可重入分布式锁，watchdog 自动续期）、RSemaphore 等。
// 项目中的分布式锁场景（如秒杀一人一单的互斥加锁）通过
// redissonClient.getLock(lockKey) 获取 RLock，配合 tryLock/unlock 使用。
// 连接参数复用 spring.redis 配置：RedisTemplate 与 Redisson
// 指向同一 Redis 实例，避免两套客户端连到不同服务器造成
// "锁在一台、数据在另一台"的错配问题。
// 使用单机模式（useSingleServer）；如升级为哨兵/集群，
// 需改用 useSentinelServers/useClusterServers 并调整配置。
// 与 RedisTemplate 的分工：RedisTemplate 管数据读写，
// Redisson 管锁等并发原语，二者各司其职。
// 当前 RabbitMQ 简化方案未使用分布式锁，该客户端保留备用。
@Configuration
public class RedissonConfig {

    // Spring 停机时自动释放 Redisson 连接与线程。
    /** 构建 Redisson 单机客户端，连接参数复用 spring.redis 配置。 */
    // destroyMethod = "shutdown"：容器关闭时释放连接池与定时线程，
    // 避免停机时 watchdog 线程泄漏。
    // 未配置密码时不发送 AUTH；Redis 开启认证时需在
    // spring.redis.password 配置匹配密码。
    // @param redisProperties Spring Boot 自动绑定的 spring.redis 配置
    // @return RedissonClient 实例
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        // redis:// 前缀是 Redisson 要求的地址协议格式
        String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();
        config.useSingleServer().setAddress(address);
        // 没配置密码时不发送 AUTH；Redis 服务开启认证时需在 spring.redis.password 配置匹配密码。
        if (StringUtils.hasText(redisProperties.getPassword())) {
            config.useSingleServer().setPassword(redisProperties.getPassword());
        }
        // create 同步建立连接并启动 watchdog 等后台设施
        return Redisson.create(config);
    }
}
