package com.hmdp.utils;

// 系统通用常量类——集中定义项目中的固定配置值，便于统一维护。
// 用 public static final 修饰，全项目通过类名直接引用。
// 修改这里的值即可全局生效，避免魔法值散落在各业务代码中。
public class SystemConstants {
    // 图片上传目录：商铺/博客图片保存的本地路径（前端 nginx 静态资源目录）
    public static final String IMAGE_UPLOAD_DIR = "D:\\code\\nginx-1.18.0\\html\\hmdp\\imgs";
    // 新注册用户的默认昵称前缀：注册时自动生成 "user_xxx" 形式的昵称
    public static final String USER_NICK_NAME_PREFIX = "user_";
    // 默认分页大小：未显式传 page size 时使用的每页条数
    public static final int DEFAULT_PAGE_SIZE = 5;
    // 最大分页大小：限制单次查询最多返回 10 条，防止恶意深翻页拖垮数据库
    public static final int MAX_PAGE_SIZE = 10;
}
