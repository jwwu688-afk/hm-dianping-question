package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(1);
        // token刷新的拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}

/*
 * 客户端请求
 *    │
 *    ▼
 * [RefreshTokenInterceptor]  order=0  拦截 /**
 *    ├─ 有 token？→ 查 Redis → 用户存在？
 *    │       ├─ 是 → 刷新 token 有效期，存 ThreadLocal
 *    │       └─ 否 → 放行（ThreadLocal 为空）
 *    ▼
 * [LoginInterceptor]  order=1  拦截除白名单外的路径
 *    ├─ ThreadLocal 有用户？→ 放行
 *    └─ 没有 → 返回 401
 *    ▼
 * Controller
 *    ▼
 * ThreadLocal 清理（afterCompletion）
 */
