// 包声明：项目根包下的临时验证测试
package com.hmdp;

// Shop：店铺实体
import com.hmdp.entity.Shop;
// ShopServiceImpl：店铺服务实现（测试临时验证片段）
import com.hmdp.service.impl.ShopServiceImpl;
// CacheClient：缓存工具类（封装穿透/击穿防护）
import com.hmdp.utils.CacheClient;
// RedisIdWorker：基于 Redis 的全局 ID 生成器
import com.hmdp.utils.RedisIdWorker;
// Test：测试方法注解
import org.junit.jupiter.api.Test;
// SpringBootTest：启动完整 Spring 上下文
import org.springframework.boot.test.context.SpringBootTest;
// Point：Redis GEO 坐标点
import org.springframework.data.geo.Point;
// RedisGeoCommands：GEO 命令与位置对象
import org.springframework.data.redis.connection.RedisGeoCommands;
// StringRedisTemplate：Redis 字符串模板
import org.springframework.data.redis.core.StringRedisTemplate;

// Resource：注入 Spring Bean
import javax.annotation.Resource;
// ArrayList：列表实现
import java.util.ArrayList;
// List：列表接口
import java.util.List;
// Map：映射接口
import java.util.Map;
// CountDownLatch：倒计时门闩（并发收口）
import java.util.concurrent.CountDownLatch;
// ExecutorService：线程池服务
import java.util.concurrent.ExecutorService;
// Executors：线程池工厂
import java.util.concurrent.Executors;
// TimeUnit：时间单位
import java.util.concurrent.TimeUnit;
// Collectors：流收集工具
import java.util.stream.Collectors;

// 缓存店铺 key 前缀常量
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
// 店铺 GEO key 前缀常量
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

// 启动完整 Spring 容器后执行（上下文加载检查兼临时验证入口）
@SpringBootTest
// 应用级临时验证测试类：验证 ID 生成、缓存预热、GEO 导入、UV 统计
class HmDianPingApplicationTests {

// 注入缓存工具 Bean
    @Resource
// 缓存工具字段
    private CacheClient cacheClient;

// 注入店铺服务 Bean
    @Resource
// 店铺服务字段
    private ShopServiceImpl shopService;

// 注入全局 ID 生成器 Bean
    @Resource
// ID 生成器字段
    private RedisIdWorker redisIdWorker;

// 注入 Redis 模板 Bean
    @Resource
// Redis 模板字段
    private StringRedisTemplate stringRedisTemplate;

// 固定 500 线程池（压 ID 生成器用）
    private ExecutorService es = Executors.newFixedThreadPool(500);

// 用例一：并发压测 ID 生成器吞吐
    @Test
// testIdWorker 方法体
    void testIdWorker() throws InterruptedException {
// 门闩计数 = 300 个任务
        CountDownLatch latch = new CountDownLatch(300);

// 定义单个任务：连续生成 100 个 ID
        Runnable task = () -> {
// 循环 100 次
            for (int i = 0; i < 100; i++) {
// 生成全局唯一 ID
                long id = redisIdWorker.nextId("order");
// 打印 ID
                System.out.println("id = " + id);
// 任务循环结束
            }
// 任务完成，门闩减一
            latch.countDown();
// lambda 定义结束
        };
// 记录起始时间
        long begin = System.currentTimeMillis();
// 提交 300 个并发任务
        for (int i = 0; i < 300; i++) {
// 提交到线程池
            es.submit(task);
// 提交循环结束
        }
// 等待全部任务完成
        latch.await();
// 记录结束时间
        long end = System.currentTimeMillis();
// 输出总耗时（吞吐参考）
        System.out.println("time = " + (end - begin));
// 用例一方法结束
    }

// 用例二：预热逻辑过期缓存（配合击穿测试用）
    @Test
// testSaveShop 方法体
    void testSaveShop() throws InterruptedException {
// 查询店铺 1
        Shop shop = shopService.getById(1L);
// 以逻辑过期 10 秒方式写入缓存
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
// 用例二方法结束
    }

// 用例三：把全部店铺按类型导入 Redis GEO（附近店铺功能的数据准备）
    @Test
// loadShopData 方法体
    void loadShopData() {
        // 1.查询店铺信息
// 查询全部店铺
        List<Shop> list = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
// 按 typeId 分组（同类型一个 GEO key）
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
// 遍历每个类型分组
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
// 取类型 ID
            Long typeId = entry.getKey();
// 拼出该类型的 GEO key
            String key = SHOP_GEO_KEY + typeId;
            // 3.2.获取同类型的店铺的集合
// 取该类型下店铺列表
            List<Shop> value = entry.getValue();
// 预分配位置集合容量
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3.写入redis GEOADD key 经度 纬度 member
// 遍历店铺组装 GEO 位置
            for (Shop shop : value) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
// 添加位置：member=店铺 ID，坐标=经纬度
                locations.add(new RedisGeoCommands.GeoLocation<>(
// member 续行：店铺 ID 字符串
                        shop.getId().toString(),
// 坐标续行：经度纬度
                        new Point(shop.getX(), shop.getY())
// GeoLocation 构造结束
                ));
// 店铺循环结束
            }
// 批量 GEOADD 写入 Redis
            stringRedisTemplate.opsForGeo().add(key, locations);
// 分组循环结束
        }
// 用例三方法结束
    }

// 用例四：验证 HyperLogLog 百万级 UV 统计
    @Test
// testHyperLogLog 方法体
    void testHyperLogLog() {
// 批量缓冲区：1000 条一批
        String[] values = new String[1000];
// 批内下标
        int j = 0;
// 循环构造 100 万条访问记录
        for (int i = 0; i < 1000000; i++) {
// 计算批内下标
            j = i % 1000;
// 生成用户访问标识
            values[j] = "user_" + i;
// 攒满一批 1000 条
            if(j == 999){
                // 发送到Redis
// PFADD 批量写入 HyperLogLog
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
// if 结束
            }
// 构造循环结束
        }
        // 统计数量
// PFCOUNT 统计基数（UV 近似值）
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
// 输出统计结果
        System.out.println("count = " + count);
// 用例四方法结束
    }
// HmDianPingApplicationTests 类结束
}
