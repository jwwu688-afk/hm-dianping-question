package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

// 博客评论实体类——对应 MySQL 表 tb_blog_comments。
// 存储探店笔记（Blog）下的评论，支持两级结构：
// parentId = 0 的是一级评论（直接评论笔记），
// parentId != 0 的是回复（挂在某条一级评论之下），answerId 指向被回复的那条评论。
// 业务角色：评论列表按 blogId 查询、点赞评论更新 liked 计数。
// 评论人信息（昵称头像）展示时从用户数据填充。
// 两级结构保证"评论+楼中楼回复"的常见社交形态。
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
@TableName("tb_blog_comments")
public class BlogComments implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    // 评论的自增主键，用于评论的唯一标识
    // 回复评论时其他记录的 answerId 会指向它
    // 全表唯一，不参与业务含义表达
    // 分页定位评论时也可使用
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户id
     */
    // 发表评论的用户 ID，关联 tb_user
    // 前端展示时用它查出评论人的昵称与头像
    // 用户注销后评论仍保留，展示为"已注销用户"
    // 评论列表按用户批量查信息减少 IO
    private Long userId;

    /**
     * 探店id
     */
    // 被评论的探店笔记 ID，关联 tb_blog
    // 按它查询某篇笔记下的全部评论
    // 笔记的 comments 计数与该表记录数对应
    // 建议与 userId 组合建索引加速查询
    private Long blogId;

    /**
     * 关联的1级评论id，如果是一级评论，则值为0
     */
    // 两级评论结构的父级标识：
    // 0 表示一级评论；非 0 表示回复，指向所属一级评论的 id
    // 前端按 parentId 分组渲染楼层与楼中楼
    // 仅支持两级，不支持无限嵌套
    private Long parentId;

    /**
     * 回复的评论id
     */
    // 具体回复的那条评论的 id（可能是一级评论，也可能是同级回复）
    // 仅当 parentId != 0 时才有意义
    // 用于回复场景展示"回复 @某人"
    // 一级评论该字段无意义
    private Long answerId;

    /**
     * 回复的内容
     */
    // 评论正文文本
    // 发表时由前端提交，长度由前端约束
    // 服务端不做长度强校验
    private String content;

    /**
     * 点赞数
     */
    // 该评论获得的点赞数量
    // 点赞接口直接对其 +1（教学项目简化处理，未做去重）
    // 初始为 0，只增不减
    // 展示在评论右侧的点赞按钮旁
    private Integer liked;

    /**
     * 状态，0：正常，1：被举报，2：禁止查看
     */
    // 评论状态标记：0 正常展示；1 被举报待处理；2 禁止查看
    // 查询评论列表时按状态过滤违规内容
    // 内容审核流程依赖该字段
    private Boolean status;

    /**
     * 创建时间
     */
    // 评论发表时间，列表按时间排序展示
    // 新评论靠前或靠后由前端选择排序方式
    // 精确到秒级
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    // 评论最后修改时间（点赞数变化、状态变更时刷新）
    // 评论内容不支持编辑，修改主要来自点赞与状态
    private LocalDateTime updateTime;


}
