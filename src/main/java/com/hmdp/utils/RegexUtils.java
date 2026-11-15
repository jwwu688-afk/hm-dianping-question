package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;

// 参数格式校验工具类——基于 RegexPatterns 中定义的正则做统一校验。
// 封装了手机号、邮箱、验证码三类常用校验，静态方法可直接调用。
// 主要在 UserController 登录流程中使用：格式不合法直接拦截，
// 避免非法参数继续进入发码、查库等后续业务。
// 命名约定：isXxxInvalid 返回 true 表示格式"无效"，false 表示合法。
// 所有校验最终都委托给私有方法 mismatch() 完成。
// 该类无状态、线程安全，可被多线程并发调用。
/**
 * @author jarvis
 */
public class RegexUtils {
    // 校验入口一：手机号格式校验。
    // 登录第一步即调用，格式非法则直接返回"手机号格式错误"，
    // 不会继续发送验证码或查询用户。
    // 用法示例：if (RegexUtils.isPhoneInvalid(phone)) return Result.fail("手机号格式错误");
    // 注意：只校验格式，不校验该手机号是否已注册。
    // 发送验证码与登录两个接口都会先走这一步。
    /**
     * 是否是无效手机格式
     * @param phone 要校验的手机号
     * @return true:符合，false：不符合
     */
    public static boolean isPhoneInvalid(String phone){
        return mismatch(phone, RegexPatterns.PHONE_REGEX);
    }
    // 校验入口二：邮箱格式校验。
    // 本项目主要使用手机验证码登录，该方法是预留的通用能力，
    // 供后续邮箱注册、绑定邮箱等扩展功能使用。
    // 与手机号校验共用 mismatch() 判定逻辑。
    // 传入 null 或空串同样返回 true（无效）。
    /**
     * 是否是无效邮箱格式
     * @param email 要校验的邮箱
     * @return true:符合，false：不符合
     */
    public static boolean isEmailInvalid(String email){
        return mismatch(email, RegexPatterns.EMAIL_REGEX);
    }

    // 校验入口三：验证码格式校验。
    // 在比对 Redis 中保存的验证码之前先做格式拦截，
    // 非法的 6 位串直接判失败，减少一次 Redis 访问。
    // 该校验对应 RegexPatterns.VERIFY_CODE_REGEX。
    // 用户登录失败提示由调用方拼接，本类不负责返回 Result。
    /**
     * 是否是无效验证码格式
     * @param code 要校验的验证码
     * @return true:符合，false：不符合
     */
    public static boolean isCodeInvalid(String code){
        return mismatch(code, RegexPatterns.VERIFY_CODE_REGEX);
    }

    // 校验是否不符合正则格式（核心判定方法）
    // 空串/空白串直接视为无效；非空则用正则取反判断。
    // 抽成私有方法是为了消除三个入口方法中的重复代码。
    // StrUtil.isBlank 来自 Hutool，比原生 isBlank 更健壮。
    // 返回 true = 不匹配（无效），返回 false = 匹配（有效）。
    // 该方法不对外暴露，格式规则统一从 RegexPatterns 获取。
    private static boolean mismatch(String str, String regex){
        if (StrUtil.isBlank(str)) {
            return true;
        }
        return !str.matches(regex);
    }
}
