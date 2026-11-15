package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

// 商铺类型实体类——对应 MySQL 表 tb_shop_type。
// 定义商铺的分类（如美食、KTV、丽人、酒店等），首页左侧分类导航栏的数据来源。
// 业务角色：
// 1. 前端首页加载分类列表（按 sort 排序展示）；
// 2. 每个分类对应一个 Redis GEO 集合（shop:geo:{typeId}），
//    "附近商铺"功能按分类查 GEO 实现按距离排序。
// 该数据基本静态、读取频繁，生产中会用 list 缓存到 Redis。
// 典型接口：ShopTypeController.queryTypeList() 返回全部分类。
// 表记录数量少（十几个分类），适合一次性全量加载。
// 缓存策略：首次查询写 Redis List，之后直接读缓存，
// 未命中才回源 MySQL，减轻数据库压力。
// 前端首页的左侧分类导航依赖该表全量数据。
// 该类随 Shop 一起构成"按类别浏览商铺"的数据基础。
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
@TableName("tb_shop_type")
public class ShopType implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    // 类型 ID，自增主键；同时作为 shop:geo:{typeId} GEO 集合的 key 后缀
    // 首页分类接口返回该 ID，前端点击分类后按它查询商铺列表
    // 分类数量固定且少量，主键也不会频繁变化
    // 后台新增分类时自增分配，前台只读
    // GEO 集合与分类一一对应，便于按类型检索附近商铺
    // 分类下线时 GEO 集合也需同步清理
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 类型名称
     */
    // 分类展示名，如"美食""娱乐"，前端导航直接显示
    // 名称在后台维护，展示层不做枚举翻译
    // 唯一性由运营规范保证，代码层不做强约束
    // 名称变更不影响缓存 key（缓存用 id）
    // 名称展示宽度由前端样式控制
    private String name;

    /**
     * 图标
     */
    // 分类图标图片地址，前端展示分类小图标
    // 一般存相对路径，由前端拼接静态资源域名
    // 与 name 一起构成导航栏的完整展示元素
    // 图标更新不影响业务逻辑
    // 图片存储于静态资源目录，由 nginx 直接访问
    private String icon;

    /**
     * 顺序
     */
    // 排序字段：数值越小越靠前，控制首页分类展示顺序
    // 运营可通过调整 sort 变更导航栏排列
    // 查询时 ORDER BY sort 升序返回
    // 相同 sort 值时按主键稳定排序
    // 排序只影响展示，不影响分类数据本身
    private Integer sort;

    /**
     * 创建时间
     */
    // 记录创建时间；@JsonIgnore 使其不序列化到前端响应中
    // 前端只需要展示字段，时间字段隐藏可精简响应体
    // 分类创建频率低，该字段主要用于后台审计
    // 缓存重建时该字段随记录一起写入 Redis
    @JsonIgnore
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    // 记录最后更新时间；同样不对前端暴露
    // 后台修改分类信息时由框架或业务代码刷新
    // 分类信息极少变动，该字段通常等于创建时间
    // 两个时间字段都做了 JSON 隐藏，保持响应简洁
    @JsonIgnore
    private LocalDateTime updateTime;


}
