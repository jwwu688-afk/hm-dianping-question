package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// 统一返回结果 DTO —— 所有 Controller 的标准响应结构。
// 前后端约定：success 为 true 时前端取 data 处理业务；
// 为 false 时前端弹出 errorMsg 提示。配合静态工厂方法
// ok()/fail() 快速构造，保证整个项目返回格式统一。
// 该类贯穿全部接口，是前后端交互的最基础数据契约。
// 所有 Controller 方法的返回值类型都是 Result。
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    /** 业务是否成功，前端据此分支处理 */
    // 注意：使用包装类型 Boolean，序列化后不会因默认值产生歧义
    private Boolean success;
    /** 失败时的错误提示信息，成功时为 null */
    // 通常直接展示给用户，如"验证码错误""用户不存在"等
    private String errorMsg;
    /** 成功时返回的业务数据，可为任意类型（对象、集合等） */
    // 使用 Object 以适配不同接口的数据类型，序列化后交给前端
    private Object data;
    /** 分页查询时的总记录数（配合 data 中的列表使用） */
    // 仅分页查询接口填充，其他接口为 null
    private Long total;

    // 成功且无数据：用于注册、修改等只需告知成败的操作
    // 返回结构：{success: true, errorMsg: null, data: null, total: null}
    // 典型调用：UserController.sign() 注册成功后直接返回
    public static Result ok(){
        return new Result(true, null, null, null);
    }
    // 成功且携带单个业务数据：用于查询详情等场景
    // 返回结构：{success: true, errorMsg: null, data: 业务对象, total: null}
    // 典型调用：ShopController.queryShopById() 返回店铺详情
    public static Result ok(Object data){
        return new Result(true, null, data, null);
    }
    // 成功且携带列表数据与总条数：用于分页查询场景
    // 返回结构：{success: true, errorMsg: null, data: 列表, total: 总数}
    // 典型调用：BlogController.hotBlog() 等列表接口
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total);
    }
    // 业务失败：携带提示信息（如"验证码错误""库存不足"）
    // 返回结构：{success: false, errorMsg: 提示, data: null, total: null}
    // 典型调用：登录校验不通过时返回 fail("验证码错误")
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null);
    }
}
