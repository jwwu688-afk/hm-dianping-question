package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

// 商铺实体类——对应 MySQL 表 tb_shop。
// 项目核心数据：商铺详情缓存（cache:shop）、附近商铺 GEO（shop:geo）、
// 探店笔记（blog.shopId）、代金券（voucher.shopId）都围绕它展开。
// 业务角色：
// 1. 缓存：详情查询走 Redis 缓存 + 互斥锁重建（防击穿）/ 逻辑过期方案；
// 2. 附近商铺：x/y 经纬度写入 shop:geo:{typeId} 的 GEO 结构，
//    支持按距离排序查询（GESEARCH）；
// 3. distance 为查询动态字段（@TableField(exist = false)），非表列。
// 更新商铺时同步清理 cache:shop 缓存，先更新库再删缓存保证一致性。
// 缓存空值方案防穿透：查不到的 id 缓存空串 2 分钟。
/**
 * <p>
 *
 * </p>
 *
 * @author jarvis
 * @since 2026-09-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop")
public class Shop implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    // 商铺 ID，自增主键；缓存 key 为 cache:shop:{id}，GEO 成员也是它
    // 详情、探店、券都以它为关联键
    // 前端路由 /shop/{id} 以该 ID 定位商铺
    // 缓存未命中时按它回源查询
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商铺名称
     */
    // 商铺名称，列表与详情页主标题展示
    // 搜索场景按名称模糊匹配
    // 名称可修改，缓存随之更新
    private String name;

    /**
     * 商铺类型的id
     */
    // 所属分类 ID，关联 tb_shop_type
    // 决定该商铺写入哪个 GEO 集合（shop:geo:{typeId}）
    // 按分类浏览商铺时以此过滤
    // 修改分类需同步迁移 GEO 集合
    private Long typeId;

    /**
     * 商铺图片，多个图片以','隔开
     */
    // 商铺照片地址串，逗号分隔，前端拆分轮播展示
    // 图片走通用上传接口存储
    // 首图作为列表页封面
    private String images;

    /**
     * 商圈，例如陆家嘴
     */
    // 商圈名称（如陆家嘴），辅助定位与筛选
    // 与 address 一起构成位置信息
    // 同商圈商铺在列表中相邻展示
    private String area;

    /**
     * 地址
     */
    // 详细地址文本，详情页展示并用于引导导航
    // 修改地址不影响已写入的 GEO 坐标
    // 坐标与地址由录入时保证一致
    private String address;

    /**
     * 经度
     */
    // 经度（x 坐标），与 y 一起构成 GEO 坐标点
    // 写入 Redis GEO 后支持"附近商铺"按距离排序
    // GEOADD 的成员是商铺 ID、坐标是 (x, y)
    private Double x;

    /**
     * 维度
     */
    // 纬度（y 坐标）；原注释笔误，实为纬度字段
    // 与 x 共同参与 GEO 距离计算
    // 数据来自前端定位或后台录入
    // 缺失坐标的商铺不出现在 GEO 结果中
    private Double y;

    /**
     * 均价，取整数
     */
    // 人均消费价格（取整），列表展示"人均 xx 元"
    // 单位为元，不涉及精度问题
    // 用 Long 避免浮点展示问题
    private Long avgPrice;

    /**
     * 销量
     */
    // 累计销量，可按销量排序推荐
    // 优惠券核销等场景可累计
    // 列表页展示"已售 x"
    private Integer sold;

    /**
     * 评论数量
     */
    // 评论数量统计，热度展示
    // 与探店笔记的评论数互不干扰
    // 数值为静态统计，不做实时更新
    private Integer comments;

    /**
     * 评分，1~5分，乘10保存，避免小数
     */
    // 评分：实际 1~5 分 × 10 存储（如 45 表示 4.5 分），避免浮点误差
    // 展示时除以 10 还原
    // 星级图标按该值渲染
    private Integer score;

    /**
     * 营业时间，例如 10:00-22:00
     */
    // 营业时间段字符串，如"10:00-22:00"，原样展示
    // 不做解析，仅文本展示
    // 是否营业的判断由前端处理
    private String openHours;

    /**
     * 创建时间
     */
    // 商铺入驻时间
    // 后台录入时生成
    // 一般不再变化
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    // 商铺信息最后修改时间；缓存更新依赖它判断新旧数据
    // 逻辑过期方案中对应 RedisData.expireTime
    // 更新接口成功后清缓存，下次查询重建
    private LocalDateTime updateTime;


    // 距离用户位置的距离（公里）：非表字段，
    // 由 GEO 查询 GEODIST 计算后填充，仅"附近商铺"接口返回
    // 按该字段升序即"由近到远"排序
    // 其他接口返回 null
    @TableField(exist = false)
    private Double distance;
}
