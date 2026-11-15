package com.hmdp.dto;

import lombok.Data;

import java.util.List;

// 滚动分页结果 DTO —— Feed 流（关注的人的笔记）分页查询的返回结构。
// Feed 流使用 Redis 的 SortedSet（score 为时间戳）按"推模式"存储，
// 无法用传统页码分页，因此采用游标式滚动分页：
// 1. list：本次查询到的笔记（Blog）列表；
// 2. minTime：本次数据中最小的时间戳，作为下次查询的游标 lastId；
// 3. offset：偏移量，处理 score 相同的边界数据，避免漏读或重复读。
// 前端每次把返回的 minTime 作为下次请求的 lastId 传入，实现无限滚动加载。
@Data
public class ScrollResult {
    /** 当前页的笔记（Blog）数据列表 */
    private List<?> list;
    /** 本次查询结果中的最小时间戳，返回给前端作为下一次请求的滚动游标 */
    private Long minTime;
    /** 相同时间戳数据的偏移量，配合游标保证边界数据不丢不重 */
    private Integer offset;
}
