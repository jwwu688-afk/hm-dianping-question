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
// ==================== 拓扑与序列化设计 ====================
// 拓扑结构：EXCHANGE(Fanout, durable) --(无 routing key)--> QUEUE(durable)。
// 消息链路：VoucherOrderServiceImpl.seckillVoucher 下单受理成功后
// rabbit.convertAndSend(EXCHANGE, "", order) → 交换机广播 →
// 订单队列 → VoucherOrderServiceImpl.onMessage（@RabbitListener）消费落库。
// 选 Fanout 的原因：当前只有一个消费者入口，广播语义最简单，
// 无需 routing key 路由规则；将来若要多个副本分摊消费，
// 可平滑换成 direct/topic + routing key，或引入多个队列。
// 按当前要求不配置 publisher confirm、死信队列与重试策略：
// 消息丢失或消费异常的兜底由运维侧监控承担。
// 消息体为 VoucherOrder 实体，经 Jackson2JsonMessageConverter 序列化为 JSON，
// 消费端使用同一个转换器反序列化，因此把信任包限定为 com.hmdp.entity，
// 防止反序列化任意类（安全考虑）。
// Bean 方法间通过方法调用（orderQueue()/orderExchange()）引用，
// 由 Spring 的 @Configuration 代理保证拿到的是容器内同一单例。
@Configuration
public class OrderRabbitConfig {
    // 使用新的交换机名称，避免与之前已经声明为 direct 类型的同名交换机冲突。
    public static final String EXCHANGE = "hmdp.order.fanout.exchange";
    public static final String QUEUE = "hmdp.order.queue";

    // durable=true：RabbitMQ 重启后仍保留交换机和队列定义。
    /** 声明订单 Fanout 交换机（持久化、不自动删除）。 */
    // Fanout 交换机忽略 routing key，把消息广播给所有绑定队列；
    // autoDelete=false：没有消费者时也不自动删除。
    // 发送端只依赖 EXCHANGE 常量，与队列解耦。
    @Bean
    public FanoutExchange orderExchange() {
        return new FanoutExchange(EXCHANGE, true, false);
    }

    /** 声明订单队列（durable，RabbitMQ 重启后队列与未消费消息仍在）。 */
    // QueueBuilder.durable 构建持久化队列；
    // 消费端 @RabbitListener 按 QUEUE 常量名监听。
    // 未配置 x-message-ttl、死信等参数，保持最小化配置。
    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    // Fanout 绑定不需要 routing key；发送到交换机的每条消息都会进入这个队列。
    /** 把订单队列绑定到 Fanout 交换机（无 binding key）。 */
    // 声明式绑定：应用启动时向 RabbitMQ 持久化该绑定关系，
    // 重复声明幂等（属性一致时不会报错）。
    @Bean
    public Binding orderBinding() {
        return BindingBuilder.bind(orderQueue()).to(orderExchange());
    }

    // VoucherOrder 以 JSON 形式发送和接收，避免使用 Java 原生序列化格式。
    /** JSON 消息转换器：发送与消费两侧共用，替代 JDK 原生序列化。 */
    // 优点：消息可读、跨语言（Python 侧也可解析）、体积更小；
    // trustedPackages 限定为 com.hmdp.entity，只允许反序列化实体包内的类，
    // 防止恶意消息触发任意类反序列化。
    // 生产与消费必须使用相同的内容类型（application/json），
    // 否则转换器不匹配会抛 MessageConversionException。
    @Bean
    public Jackson2JsonMessageConverter orderMessageConverter() {
        return new Jackson2JsonMessageConverter("com.hmdp.entity");
    }
}
