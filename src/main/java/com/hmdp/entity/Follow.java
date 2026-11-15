package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

// 关注关系实体类——对应 MySQL 表 tb_follow。
// 记录"谁关注了谁"的一条关注关系，是关注/取关功能的持久化载体。
// 业务角色：
// 1. 关注/取关接口按 (userId, followUserId) 增删该表记录；
// 2. 共同关注功能：对两个用户的关注列表（followUserId）求交集（Redis Set）；
// 3. Feed 流推模式：用户发笔记时查该表找到粉丝，逐个写入粉丝的收件箱。
// 注意：该表与 Redis 的关注 Set（follow:{userId}）配合使用，Redis 缓存关注列表
// 用于快速判断是否已关注与计算共同关注。
// 典型接口：FollowController.follow() 关注/取关、followCommons() 共同关注。
// 与 Redis 关注 Set 的写入在同一事务边界内完成，保证两侧数据一致。
// 本表数据量随关注行为线性增长，读多写少，适合按 userId 建索引加速查询。
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
@TableName("tb_follow")
public class Follow implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    // 数据库自增主键，仅用于唯一标识这条关注关系记录
    // 业务上不直接使用它做关联，关联靠 userId + followUserId
    // 由 MyBatis-Plus 的 IdType.AUTO 配置自增写入
    // 无业务语义，仅作行级唯一标识
    // 表数据删除后 ID 不会回退
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户id
     */
    // 发起关注的人（粉丝）的用户 ID
    // 同一个 userId 可有多条记录（关注了多个博主）
    // 关注成功后同时向 Redis Set follow:{userId} 写入 followUserId
    // 取关时同时删除 MySQL 记录与 Redis Set 成员
    // 查询"我的关注"列表时按该字段过滤
    private Long userId;

    /**
     * 关联的用户id
     */
    // 被关注的博主（目标用户）的用户 ID
    // (userId, followUserId) 组合业务上应唯一，防止重复关注
    // 共同关注计算时用它与其他用户的关注列表求交集
    // 也可用于统计博主的粉丝列表
    // Feed 推模式发笔记时按它反查粉丝集合
    // 关注列表接口返回的目标用户即该字段
    private Long followUserId;

    /**
     * 创建时间
     */
    // 关注动作发生的时间，可用于关注列表按时间排序展示
    // 取关时整条记录删除，该字段随之消失
    // 不需要更新时间字段：关注关系只增删、不修改
    // 新增记录时由业务代码手动赋值
    // 无 updateTime 字段：本实体无更新语义
    private LocalDateTime createTime;


}
