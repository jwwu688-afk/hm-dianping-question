# RabbitMQ 秒杀订单最简流程

## 当前流程

```text
用户调用秒杀接口
    → 生成订单 ID 和 VoucherOrder
    → 执行 Redis Lua 脚本
    → Lua 判断库存和重复下单
    → Lua 预扣 Redis 库存
    → Java 发送 VoucherOrder 到 Fanout 交换机
    → Fanout 交换机广播到绑定的订单队列
    → 接口返回订单 ID
    → RabbitMQ 消费者收到 VoucherOrder
    → 扣减 MySQL 库存
    → 插入订单
```

这个版本只用于展示完整主流程，没有生产者确认、死信队列、消费重试、Redisson 分布式锁、消费幂等或失败补偿。

## 代码位置

- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
  - `seckillVoucher`：生成订单、执行 Lua、发送 RabbitMQ。
  - `onMessage`：接收 RabbitMQ 中的订单。
  - `createVoucherOrder`：扣减 MySQL 库存并插入订单。
- `src/main/resources/seckill.lua`：检查 Redis 库存和重复购买，成功后预扣库存。
- `src/main/java/com/hmdp/config/OrderRabbitConfig.java`：声明 Fanout 交换机、队列、绑定和 JSON 转换器。
- `src/main/resources/application.yaml`：RabbitMQ、Redis、MySQL 连接配置。

原 Redis Stream Java 和 Lua 代码仍然作为注释保留，不会运行。项目中没有 `com.hmdp.messaging` 文件夹。

## 下单过程

接口：

```text
POST /voucher-order/seckill/{voucherId}
```

1. 从 `UserHolder` 获取当前登录用户。
2. 使用 `RedisIdWorker` 生成订单 ID。
3. 创建包含订单 ID、用户 ID、优惠券 ID 的 `VoucherOrder`。
4. 执行 Lua，传入：
   - `seckill:stock:{voucherId}`
   - `seckill:order:{voucherId}`
5. Lua 返回 `0` 时，调用：

   ```java
   rabbitTemplate.convertAndSend(
       "hmdp.order.fanout.exchange",
       "",
       voucherOrder
   );
   ```

6. Java 返回订单 ID。

Lua 返回值：

| 返回值 | 含义 |
| --- | --- |
| `0` | 预扣成功 |
| `1` | 库存不足 |
| `2` | 重复下单 |
| `3` | Redis 库存未初始化 |
| `4` | 参数或 Redis 数据异常 |

## RabbitMQ 消费过程

RabbitMQ 拓扑：

| 类型 | 名称 |
| --- | --- |
| FanoutExchange | `hmdp.order.fanout.exchange` |
| Queue | `hmdp.order.queue` |

Fanout 交换机忽略 routing key，因此生产者发送时传空字符串。当前只绑定一个订单队列；如果以后再绑定其他队列，每个绑定队列都会收到一份订单消息。

消费者监听：

```java
@RabbitListener(queues = "hmdp.order.queue")
public void onMessage(VoucherOrder order)
```

收到订单后只执行两步：

```text
UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ?
INSERT INTO tb_voucher_order ...
```

## IDEA 启动

1. 在 IDEA 打开 `D:\code\hm-dianping`。
2. 在 Maven 窗口点击 Reload All Maven Projects。
3. 确认 Docker 中 MySQL、Redis、RabbitMQ 已启动。
4. 核对 `application.yaml` 中的连接地址、用户名和密码。
5. 运行 `HmDianPingApplication`。
6. 浏览器打开 `http://localhost:15672`，确认 `hmdp.order.queue` 已自动创建。

RabbitMQ 的 Java 连接端口是 `5672`，管理页面端口是 `15672`。

## 测试

单元测试：

```powershell
mvn -Dtest=VoucherOrderServiceImplTest test
```

使用隔离 Redis 和 RabbitMQ 容器测试完整主流程：

```powershell
mvn -Pintegration-test clean verify
```
