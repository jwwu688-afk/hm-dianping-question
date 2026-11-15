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
@Configuration
public class RedissonConfig {

    // Spring 停机时自动释放 Redisson 连接与线程。
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();
        config.useSingleServer().setAddress(address);
        // 没配置密码时不发送 AUTH；Redis 服务开启认证时需在 spring.redis.password 配置匹配密码。
        if (StringUtils.hasText(redisProperties.getPassword())) {
            config.useSingleServer().setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }
}
