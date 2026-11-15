package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// 登录校验拦截器（内层拦截器）——只负责"拦不拦"，不负责解析用户。
// 双层拦截器设计中它注册在后、拦截范围更小（只拦截需登录的路径）。
// 到达这里时外层 RefreshTokenInterceptor 已经完成：
// 解析 token -> 查 Redis -> 写 ThreadLocal 三件事，本类直接读 UserHolder 判断。
// 若 UserHolder 为空则返回 401，前端据此跳转登录页面。
// 相比单拦截器方案：避免了"每个请求都查 Redis + 每个请求都强制登录"的问题。
// 类似 Spring Security 中认证与授权分离的思路。
// 职责分离的好处：无需登录的路径（如首页、浏览商铺）也能走外层拦截器，
// 借此完成 token 续期，而不会被强制登录。
// 注册位置：MvcConfig 中 addInterceptors 后注册，excludePathPatterns
// 排除 user/code 等无需登录的接口。
// 该类无成员状态，判断依据完全来自 ThreadLocal，天然线程安全。
public class LoginInterceptor implements HandlerInterceptor {

    // 请求进入 Controller 前调用：
    // UserHolder 中有用户（外层已写入）则放行；否则返回 401 拦截。
    // preHandle 返回 false 时请求不会再进入 Controller。
    // 这里只读 ThreadLocal，不访问 Redis，性能开销极小。
    // 判断逻辑极简：所有复杂工作都由外层拦截器提前完成。
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 当前线程无用户，说明未登录：直接响应 401 状态码，请求到此结束
        // 前端收到 401 后会引导用户跳转登录页
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        // 已登录，放行进入后续 Controller 处理
        // 业务代码中通过 UserHolder.getUser() 即可拿到当前用户
        // 注意：这里不需要 remove，外层拦截器的 afterCompletion 已统一清理
        return true;
    }
}
