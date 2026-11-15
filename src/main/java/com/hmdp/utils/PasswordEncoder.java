package com.hmdp.utils;


import cn.hutool.core.util.RandomUtil;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

// 密码加密工具类——"随机盐 + MD5"的加盐哈希方案。
// 存储流程：注册时 encode() 生成随机盐，将 盐+"@"+MD5(密码+盐) 整体存库；
// 每个用户的盐都不同，即使两人密码相同，库中密文也不同，
// 可有效对抗彩虹表等预计算破解手段。
// 比对流程：登录时 matches() 从密文中拆出盐，用同样方式加密明文后
// 比对整段密文是否一致。
public class PasswordEncoder {

    // 对外入口：对明文密码加盐哈希（用于注册/修改密码）
    // 每次调用生成新的随机盐，同一密码两次加密结果不同
    public static String encode(String password) {
        // 生成盐
        // 20 位随机字符串，保证每个用户盐值唯一的概率足够大
        String salt = RandomUtil.randomString(20);
        // 加密
        // 委托给带盐的重载方法完成最终密文组装
        return encode(password,salt);
    }
    // 核心加密方法：密文 = 盐 + "@" + MD5(密码 + 盐)
    // "@" 只作分隔符，便于比对时从密文中拆出盐
    private static String encode(String password, String salt) {
        // 加密
        // DigestUtils 是 Spring 提供的 MD5 工具，输出 32 位十六进制串
        return salt + "@" + DigestUtils.md5DigestAsHex((password + salt).getBytes(StandardCharsets.UTF_8));
    }
    // 密码比对：登录校验时判断明文密码是否与库中密文匹配
    // 不解密（MD5 不可逆），而是用库中的盐重新加密明文再比较
    public static Boolean matches(String encodedPassword, String rawPassword) {
        // 任何一个为 null 都直接判不匹配，避免空指针
        if (encodedPassword == null || rawPassword == null) {
            return false;
        }
        // 密文必须包含分隔符"@"，否则说明不是本工具加密的数据
        if(!encodedPassword.contains("@")){
            throw new RuntimeException("密码格式不正确！");
        }
        String[] arr = encodedPassword.split("@");
        // 获取盐
        // 密文前半段是盐，用同一盐重算即可复现整段密文
        String salt = arr[0];
        // 比较
        // 用重算的密文与库中密文整体比对，一致则密码正确
        return encodedPassword.equals(encode(rawPassword, salt));
    }
}
