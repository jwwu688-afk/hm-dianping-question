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

// 探店笔记实体类——对应 MySQL 表 tb_blog。
// 用户发布的探店图文内容，是社区模块的核心数据。
// 业务角色：
// 1. 首页"热榜"按 liked 排序取前 10；
// 2. 点赞功能：点赞数存本表 liked，点赞人列表存 Redis SortedSet
//    （blog:liked:{blogId}，score 为点赞时间戳，支持"最早点赞的人"排序）；
// 3. Feed 流：发笔记时推送到粉丝收件箱（feed:{userId}），滚动分页用 ScrollResult；
// 4. icon/name/isLike 是非表字段（@TableField(exist = false)），展示时填充。
// 点赞一致性：先写 Redis 集合再更新 DB 计数，计数可重算校准。
// isLike 只对"当前请求用户"有意义，每次查询动态计算。
// 笔记删除在本项目中未开放，数据只增不减。
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
@TableName("tb_blog")
public class Blog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    // 笔记 ID，自增主键；点赞、评论、Feed 流都以它为关联键
    // 详情页按 id 直查缓存
    // Feed 收件箱中的成员即该 ID
    // 点赞集合 key 的后缀也是它
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 商户id
     */
    // 探店关联的商铺 ID，关联 tb_shop
    // 笔记详情页据此展示"相关商铺"入口
    // 允许为空：纯感想类笔记可不关联商铺
    // 关联商铺时前端展示商铺入口卡
    private Long shopId;
    /**
     * 用户id
     */
    // 笔记作者的用户 ID，关联 tb_user
    // 展示作者信息、判断笔记归属
    // Feed 流推模式按它找作者、按粉丝写收件箱
    // 用户主页按它查询"我发布的笔记"
    private Long userId;
    /**
     * 用户图标
     */
    // 作者头像：非表字段，查列表时从用户信息填充，
    // 避免每次都关联查询用户表
    // 填充逻辑在 BlogServiceImpl 的公共方法中统一处理
    // 头像不存在时前端展示默认头像
    // 批量填充避免 N+1 查询
    @TableField(exist = false)
    private String icon;
    /**
     * 用户姓名
     */
    // 作者昵称：非表字段，同样查询时填充用于展示
    // 与 icon 一起构成评论区/列表的作者信息
    // 从 tb_user 的 nick_name 字段读取
    // 与缓存中的 UserDTO 字段一致
    @TableField(exist = false)
    private String name;
    /**
     * 是否点赞过了
     */
    // 当前登录用户是否已对该笔记点赞：非表字段，动态计算
    // 依据：Redis blog:liked 集合中是否包含当前用户
    @TableField(exist = false)
    private Boolean isLike;

    /**
     * 标题
     */
    // 笔记标题，列表与详情页展示
    // 发表时必填
    // 标题展示在卡片顶部
    private String title;

    /**
     * 探店的照片，最多9张，多张以","隔开
     */
    // 图片地址串：最多 9 张，逗号分隔，前端拆分展示为九宫格
    // 图片先走通用上传接口拿到地址
    // 前端按逗号 split 后渲染图片列表
    // 超过 9 张由发表页限制
    private String images;

    /**
     * 探店的文字描述
     */
    // 笔记正文文本
    // 支持较长内容，前端做滚动展示
    // 纯文本存储，不带格式标记
    private String content;

    /**
     * 点赞数量
     */
    // 点赞数：点赞/取消时与 Redis 集合同步增减
    // 热榜接口按该字段降序取前 10 篇
    private Integer liked;

    /**
     * 评论数量
     */
    // 评论数：发表评论时 +1，展示笔记的热度
    private Integer comments;

    /**
     * 创建时间
     */
    // 发布时间；Feed 流按发布时间戳作为 SortedSet 的 score
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    // 笔记最后修改时间
    // 编辑功能开放时刷新
    // 当前版本未提供编辑接口
    // 保留字段以备扩展
    private LocalDateTime updateTime;


}
