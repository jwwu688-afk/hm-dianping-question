package com.hmdp.utils;

// 通用正则表达式常量池——集中定义各参数格式的校验规则。
// 设计为 abstract 类（无法实例化），只通过类名静态引用。
// 配合 RegexUtils 工具类使用，服务端校验防止恶意提交非法格式。
// 常量均为 public static final，正则规则集中维护。
// 修改正则需同步评估已有用户的存量数据兼容性。
/**
 * @author jarvis
 */
public abstract class RegexPatterns {
    // 手机号正则：校验 11 位国内手机号，
    // 号段覆盖 13x/14x/15x/16x/17x/18x/19x 的合法子集，
    // 登录注册时校验用户提交的手机号格式是否合法。
    /**
     * 手机号正则
     */
    public static final String PHONE_REGEX = "^1([38][0-9]|4[579]|5[0-3,5-9]|6[6]|7[0135678]|9[89])\\d{8}$";
    // 邮箱正则：校验常规邮箱格式（用户名@域名.后缀），
    // 可用于邮箱注册、找回密码等场景的参数校验。
    /**
     * 邮箱正则
     */
    public static final String EMAIL_REGEX = "^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$";
    // 密码正则：4~32 位的字母、数字、下划线，
    // 注册或修改密码时校验密码强度与长度合法性。
    /**
     * 密码正则。4~32位的字母、数字、下划线
     */
    public static final String PASSWORD_REGEX = "^\\w{4,32}$";
    // 验证码正则：6 位数字或字母，
    // 登录时校验用户提交的短信验证码格式，快速拦截非法输入。
    /**
     * 验证码正则, 6位数字或字母
     */
    public static final String VERIFY_CODE_REGEX = "^[a-zA-Z\\d]{6}$";

}
