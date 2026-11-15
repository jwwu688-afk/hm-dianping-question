package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/** Web MVC 拦截器链配置：注册登录校验与 token 续期两个拦截器。 */
// ==================== 拦截器链设计 ====================
// 执行顺序由 order 决定（数字小者先执行），与注册先后无关：
// 1) RefreshTokenInterceptor，order=0，拦截 /**（含登录接口本身）：
//    从请求头取 token → 查 Redis → 用户存在则刷新 token 有效期
//    （续期 30 分钟）并把用户存入 ThreadLocal；
//    无 token 或用户不存在也放行（ThreadLocal 为空），交由下一层判断。
// 2) LoginInterceptor，order=1，只拦业务路径：
//    通过 excludePathPatterns 放行白名单（/shop/**、/voucher/**、
//    /shop-type/**、/upload/**、/blog/hot、/user/code、/user/login），
//    其余路径校验 ThreadLocal 是否有用户，没有则返回 401。
// 拆成两层的意义：续期逻辑对携带 token 的匿名请求也要执行，
// 而登录校验只对需要登录的接口生效，两层职责解耦。
// Token 续期采用"每次请求都重置 TTL"的滑动过期策略。
// 白名单中的路径不校验登录，但仍会经过 order=0 的续期拦截器。
// ThreadLocal 在 afterCompletion 中清理（RefreshTokenInterceptor 实现），
// 防止 Tomcat 线程池复用线程导致的用户串号。
// stringRedisTemplate 通过构造参数注入续期拦截器使用。
// 请求流程示意见文件末尾的 ASCII 图（与上述文字一致）。
// 新增需要登录的模块无需改动本类；新增免登录接口则要加白名单。
// 白名单维护 checklist：新增公开接口 → 加 excludePathPatterns →
// 自测匿名可访问 → 回归确认其他路径仍返回 401。
// 续期动作对性能敏感：仅一次 Redis GET + 一次 EXPIRE，可放心全局拦截。
// 若未来引入 API 网关做统一鉴权，本类两层拦截器可整体下线。
// 请求路径统计：白名单接口多（7 条规则），改动时注意通配符边界，
// 如 /blog/hot 精确匹配而 /shop/** 为前缀通配。
// 本类实现 WebMvcConfigurer 接口而非继承 WebMvcConfigurationSupport，
// 保留 Spring Boot 的默认 MVC 配置（静态资源、消息转换器等）。
// 修改拦截器顺序或白名单前，先阅读文件末尾的流程图确认影响面。
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 注册两个拦截器：order=0 的续期拦截器先于 order=1 的登录拦截器执行。 */
    // 注意：注册顺序（先 Login 后 Refresh）与执行顺序（Refresh 先于 Login）
    // 无关，Spring 按 order 值排序执行。
    // LoginInterceptor 的 excludePathPatterns 即登录白名单，
    // 新增免登录接口时需同步维护这份列表；
    // addPathPatterns("/**") + excludePathPatterns 组合实现"默认全拦、白名单放行"。
    // 续期拦截器只 addPathPatterns("/**")，对所有请求生效（含白名单路径）。
    // 两个拦截器均为 new 出来的普通对象，不是 Spring Bean，
    // 因此续期拦截器需要的 StringRedisTemplate 由本类注入后传入。
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
        // order(1)：第二层，仅校验 ThreadLocal 中是否有用户
        // token刷新的拦截器
        // order(0)：续期拦截器必须最先执行，否则登录校验拿不到用户
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
