package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 秒杀订单最小 RabbitMQ 配置。
 *
 * <p>生产者把订单发送到 fanout 交换机，交换机会把消息复制到所有绑定队列。
 * 当前只绑定一个订单队列，由一个消费者入口处理。这里不配置确认、死信或重试。</p>
 */
@Configuration
public class OrderRabbitConfig {
    // 使用新的交换机名称，避免与之前已经声明为 direct 类型的同名交换机冲突。
    public static final String EXCHANGE = "hmdp.order.fanout.exchange";
    public static final String QUEUE = "hmdp.order.queue";

    // durable=true：RabbitMQ 重启后仍保留交换机和队列定义。
    @Bean
    public FanoutExchange orderExchange() {
        return new FanoutExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    // Fanout 绑定不需要 routing key；发送到交换机的每条消息都会进入这个队列。
    @Bean
    public Binding orderBinding() {
        return BindingBuilder.bind(orderQueue()).to(orderExchange());
    }

    // VoucherOrder 以 JSON 形式发送和接收，避免使用 Java 原生序列化格式。
    @Bean
    public Jackson2JsonMessageConverter orderMessageConverter() {
        return new Jackson2JsonMessageConverter("com.hmdp.entity");
    }
}
