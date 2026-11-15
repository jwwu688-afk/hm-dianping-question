package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * <p>商铺服务：商铺详情缓存防护三段式 + 附近商铺 GEO 检索，详细说明见方法内注释。</p>
 *
 * @author jarvis
 * @since 2026-09-16
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    // Redis 客户端：附近商铺的 GEO 搜索、缓存删除都依赖它
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    // 缓存工具类：三段式防护的具体实现都在这里，本类只做策略选择
    private CacheClient cacheClient;

    /** 根据 id 查询商铺详情；缓存防护三段式的选型说明见方法内注释。 */
    @Override
    public Result queryById(Long id) {
        // —— 缓存防护三段式：本方法启用第一段，后两段以注释保留备选 ——
        // 1) 穿透防护 queryWithPassThrough：未命中时查库，查不到则把空值写入缓存并给短 TTL，
        //    防止恶意请求不存在的 id 反复打到数据库；
        // 2) 击穿防护（互斥锁）queryWithMutex：热点 key 失效瞬间只放一个线程重建缓存，
        //    其余线程等待，避免瞬时高并发压垮数据库；
        // 3) 击穿防护（逻辑过期）queryWithLogicalExpire：缓存永不物理过期，
        //    过期后异步重建并返回旧值，牺牲一点一致性换取彻底无阻塞。
        // 三者的取舍：穿透防护实现最简单、覆盖绝大多数场景；互斥锁强一致但有等待；
        // 逻辑过期无阻塞但需要预热缓存并容忍短暂的旧数据。
        // 本项目是教学性质，默认用穿透防护；生产高并发热点场景建议逻辑过期。
        // 解决缓存穿透
        // （未命中 → 查库 → 查不到则缓存空值，把恶意 id 的重复请求挡在缓存层）
        // 空值缓存的 TTL 由 CacheClient 内部的 CACHE_NULL_TTL 控制，这里传的是正常数据的 TTL
        // 若想切换防击穿策略，直接把下面的调用换成 queryWithMutex 或 queryWithLogicalExpire 即可
        // CACHE_SHOP_TTL（30 分钟）是正常数据的缓存过期时间，短 TTL 也能缩短脏数据的存活时间
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // dbFallback 传入 this::getById：缓存未命中时由工具类回调本 Service 的查库方法
        // key 最终形如 cache:shop:{id}，与更新商铺时删除缓存的 key 拼接规则保持一致

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        // 注意：三种方案同时只能启用一种，切换时注释/解注释对应调用即可
        // 互斥锁与逻辑过期两种实现分别见 CacheClient 的 queryWithMutex / queryWithLogicalExpire
        // 三段式策略下，queryWithPassThrough 返回的 null 表示“商铺确实不存在”
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        // 命中缓存或回源成功，都会拿到完整 Shop 实体
        return Result.ok(shop);
    }

    /** 更新商铺信息，一致性方案采用「先更新数据库，再删除缓存」，理由见方法内注释。 */
    @Override
    @Transactional
    public Result update(Shop shop) {
        // 事务保证 updateById 的原子性；缓存删除不在数据库事务内，
        // 即便删除失败，也只是旧缓存多存活一个 TTL 周期，由回源兜底
        // 前置校验：没有 id 就无法定位要更新的商铺，也无法删除对应缓存
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 先库后缓存的顺序不能反：若先删缓存再更新库，并发读会在库更新前回源写入旧值
        // 1.更新数据库
        // updateById 只更新实体里非空的字段，避免把前端未提交的字段刷成 null
        // 参数 shop 由前端提交，id 必填已在上面校验过
        updateById(shop);
        // 2.删除缓存
        // 为什么删缓存而不是更新缓存：写库后立刻更新缓存可能把中间态写入，
        // 且很多写操作之后并不马上被读，更新缓存是浪费；删掉后下一次读请求
        // 未命中缓存会自动回源重建，天然最终一致
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // 只删不更新：下次读取时回源重建，避免把数据库尚未提交/回滚的数据写进缓存
        // 到这里缓存已失效，下一笔读请求会带回最新数据
        return Result.ok();
    }

    /** 按类型分页查询商铺；带坐标时用 Redis GEO 按「距离由近到远」排序，协议细节见方法内注释。 */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 分支说明：x/y 是前端定位授权后才有的值，未授权或未开启定位时按普通分页浏览
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            // 按商铺类型过滤后直接分页，由 MyBatis-Plus 生成 LIMIT 语句
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            // DEFAULT_PAGE_SIZE 是系统约定的每页条数
            return Result.ok(page.getRecords());
        }

        // 走到这里说明携带了有效坐标，后续全部走 Redis GEO 路径

        // 2.计算分页参数
        // 为什么用 GEO 而不是 MySQL：商铺位置基本不变，每种类型的商铺坐标按 type_id
        // 分组预先 GEOADD 进 Redis；一次 GEOSEARCH 就能完成「范围过滤 + 距离计算 + 排序」，
        // 避免在 MySQL 里对经纬度逐行做三角函数计算
        // 每种类型一个 key 的原因：GEOSEARCH 只能在单个集合内搜索，按类型分 key
        // 才能把“同类型”过滤下推给 Redis，同时避免单个 key 过大
        // 整体流程：算出 from/end → GEOSEARCH 取前 end 条 → 内存截取本页 → 回查 MySQL → 回填距离
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        // 本页末尾的序号（不含），同时作为 GEOSEARCH 的 LIMIT 参数
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        // 每种商铺类型对应一个独立的 GEO 集合（key = 前缀 + typeId），实现“按类型分组”
        String key = SHOP_GEO_KEY + typeId;
        // GEOSEARCH：以 (x, y) 为参照点、5000 为搜索半径，结果附带距离并按距离升序排列
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        // 参照点：用户当前经纬度
                        GeoReference.fromCoordinate(x, y),
                        // 搜索半径固定 5000（米），覆盖“附近”的常见理解
                        new Distance(5000),
                        // includeDistance 返回距离；limit(end) 只取前 end 条（GEOSEARCH 不支持 offset）
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        // 兜底：正常 GEOSEARCH 不会返回 null，保险起见返回空列表
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        // GEOSEARCH 的结果已按距离升序，list 里是 [shopId, distance] 对
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        // getContent() 拿到 GeoResult 列表，每个元素包含 member(shopId) 与 distance
        // 返回条数不足一页的起点，说明已经翻到末尾，没有下一页数据了
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        // id 列表用于回查 MySQL，distanceMap 暂存每个店铺的距离，最后回填到 Shop 对象
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        // skip(from)：内存分页——跳过前几页的数据，只处理本页区间
        // 例如第 2 页（current=2，每页 5 条）时 from=5、end=10，跳过前 5 条再取 5 条
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            // GEO 的 member 存的就是店铺 id 字符串
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            // 以店铺 id 为键暂存距离，回填时按 id 取回
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 循环结束：ids 保持距离升序，distanceMap 与 ids 一一对应
        // 5.根据id查询Shop
        // IN 查询不保证顺序，必须用 ORDER BY FIELD(id,...) 保持 GEO 的距离升序，
        // 否则“由近到远”的排序会被数据库打乱
        String idStr = StrUtil.join(",", ids);
        // 把 id 拼成逗号串，作为 ORDER BY FIELD 的参数；last() 会把它拼接到 SQL 末尾
        // FIELD(id, 5, 1) 会按 5 在前、1 在后的顺序返回结果，从而保持距离升序
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        // 把 GEO 算出的距离回填到每个商铺实体，随详情一起返回给前端
        for (Shop shop : shops) {
            // 键是字符串化的店铺 id；距离取数值部分，默认单位米
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        // 组装完成：shops 的顺序 = 距离升序，且每个实体都带上了 distance 字段
        return Result.ok(shops);
    }
}
// —— 本类小结 ——
// queryById：详情查询，走 CacheClient 的穿透防护三段式；
// update：先更新数据库再删除缓存，保证最终一致；
// queryShopByType：无坐标走 MySQL 分页，有坐标走 Redis GEO 的距离排序 + 内存分页。
// GEO 数据的写入在店铺入驻/导入时完成（GEOADD），本类只负责查询侧。
// 三个方法的共同点：都不把“为什么”埋进代码细节，策略选型理由全部写在对应注释里。
// 复杂度集中在 CacheClient，本类保持薄壳，方便单元测试与后续替换缓存实现。
