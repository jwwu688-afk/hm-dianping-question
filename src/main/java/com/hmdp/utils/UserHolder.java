package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

// 当前登录用户持有工具类——基于 ThreadLocal 在同一线程内共享用户信息。
// 存取流程：RefreshTokenInterceptor（外层拦截器）在请求进入时从 Redis
// 解析出 UserDTO 并调用 saveUser() 写入；业务层（Service/Controller）
// 随时调用 getUser() 获取当前用户，无需再查库或传参；请求结束后调用
// removeUser() 清理。
// 风险提示：Tomcat 使用线程池复用线程，若请求结束不调用 removeUser()，
// 下一次复用该线程的请求可能读到"上一个用户"的数据，造成串号；
// 因此必须在 afterCompletion 中清理。
public class UserHolder {
    // 每个 Thread 各自持有独立的 UserDTO 副本，天然线程隔离、无锁竞争
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    // 保存当前登录用户到当前线程（由拦截器在请求开始时调用）
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    // 获取当前线程绑定的登录用户；未登录时返回 null，
    // 工具类/Service 中可直接拿到 userId、昵称等展示字段
    public static UserDTO getUser(){
        return tl.get();
    }

    // 清理当前线程绑定的用户信息（由拦截器在请求结束时调用），
    // 防止线程池复用导致的内存泄漏与用户数据串号
    public static void removeUser(){
        tl.remove();
    }
}
