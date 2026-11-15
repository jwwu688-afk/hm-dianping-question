package com.hmdp.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis-Plus 插件配置：注册分页插件，使 Page 查询真正生效。 */
// ==================== MyBatis-Plus 插件配置 ====================
// PaginationInnerInterceptor 负责把 Page 对象的查询改写为
// 带 LIMIT 的分页 SQL，并自动执行 count 查询填充 total 总数。
// 指定 DbType.MYSQL 方言，生成 MySQL 语法的分页语句。
// BlogController、ShopController 等处的 new Page<>(current, size)
// 分页查询都依赖本插件；缺少它时分页不生效、直接返回全量数据。
// 注意：MybatisPlusInterceptor 是插件容器，
// 多个 InnerInterceptor 按添加顺序依次生效。
@Configuration
public class MybatisConfig {
    /** MyBatis-Plus 拦截器插件 Bean。 */
    // 目前仅挂载 MySQL 分页插件；
    // 如需乐观锁（OptimisticLockerInnerInterceptor）、
    // 防全表更新（BlockAttackInnerInterceptor）等，
    // 继续在返回的 interceptor 上 addInnerInterceptor 追加即可。
    // @return MybatisPlusInterceptor 实例，交由 Spring 管理并接入 MyBatis
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 插件容器：多个内部拦截器按 addInnerInterceptor 的顺序链式生效
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页内部拦截器，指定 MySQL 方言
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
