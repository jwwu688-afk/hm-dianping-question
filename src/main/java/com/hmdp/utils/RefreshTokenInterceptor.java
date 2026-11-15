package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

// token 续期拦截器（外层拦截器）——双层拦截器设计的第一层。
// 拦截 /** 所有请求，职责：读取 token、从 Redis 解析用户、写入 ThreadLocal、刷新有效期。
// 它本身不拦截任何请求（恒返回 true），只做"准备工作"；
// 真正的登录校验交给内层 LoginInterceptor 完成。
// 这样设计是因为：token 续期必须对"所有请求"生效（包括浏览首页等
// 免登录请求），如果放在校验拦截器里，用户不登录就无法续期。
// 注册位置：MvcConfig 中最先注册，excludePathPatterns 不配置任何路径。
// 执行顺序：外层（本类 preHandle）-> 内层 LoginInterceptor -> Controller。
// 注意：本类不能拦截静态资源时的性能损耗——查 Redis 只在带 token 时发生。
public class RefreshTokenInterceptor implements HandlerInterceptor {

    // Redis 操作模板，由 MvcConfig 构造时注入（拦截器非 Spring 管理，用构造器传参）
    private StringRedisTemplate stringRedisTemplate;

    // 构造注入 StringRedisTemplate，保证本类可用而无需标注 @Component
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 请求前置处理：尝试解析登录用户并写入 ThreadLocal，无论成败都放行
    // 该方法对每个请求都会执行（拦截 /**）
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中的token
        // 前端登录后把 token 存在请求头 authorization 中随请求携带
        String token = request.getHeader("authorization");
        // 请求头为空说明可能未登录：不写入用户，直接放行（后续由内层拦截器决定是否拦截）
        // 这里返回 true 而不是 false，正是"外层不做拦截"的体现
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // 2.基于TOKEN获取redis中的用户
        // 用户信息以 Hash 形式存储，key 为 login:token:{token}
        // Hash 结构便于只读取部分字段，也支持增量更新用户信息
        String key  = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 3.判断用户是否存在
        // Redis 中无该 token 对应数据（已过期或非法 token）：同样放行，交给内层拦截
        // userMap 为空也意味着 ThreadLocal 中没有用户，内层会返回 401
        if (userMap.isEmpty()) {
            return true;
        }
        // 5.将查询到的hash数据转为UserDTO
        // Hutool 的 fillBeanWithMap 把 Hash 字段填充到 UserDTO 对象
        // 只保留 id/nickName/icon 三个字段，敏感数据不会进入业务层
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 6.存在，保存用户信息到 ThreadLocal
        // 之后业务代码可通过 UserHolder.getUser() 获取当前用户
        UserHolder.saveUser(userDTO);
        // 7.刷新token有效期
        // 只要用户有活动就续期，实现"活跃用户不掉线"
        // TTL 单位为分钟，取值来自 RedisConstants.LOGIN_USER_TTL
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8.放行
        // 无论是否解析出用户都返回 true，拦截决策交给内层 LoginInterceptor
        return true;
    }

    // 请求完成后清理 ThreadLocal：
    // Tomcat 线程池会复用线程，不清除会导致下一个请求读到旧用户（串号），
    // 同时防止内存泄漏。
    // afterCompletion 在视图渲染完成后必定执行，是清理的最佳时机。
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
