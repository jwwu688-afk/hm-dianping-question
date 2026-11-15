package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 黑马点评（hm-dianping）Spring Boot 启动类。 */
// ==================== 应用入口 ====================
// @SpringBootApplication 是组合注解：
//   @Configuration：本类可作为配置类；
//   @EnableAutoConfiguration：按 classpath 自动装配（Web/Redis/MyBatis 等）；
//   @ComponentScan：默认扫描本类所在包 com.hmdp 及其子包，
//   覆盖 controller/service/config/mapper 等全部组件。
// @MapperScan("com.hmdp.mapper")：把该包下的 Mapper 接口注册为
// MyBatis 代理 Bean，配合 MyBatis-Plus 的 BaseMapper 提供数据访问能力。
// 配置来源：application.yaml（数据源、Redis、MyBatis-Plus、shop.qa.url 等）。
@MapperScan("com.hmdp.mapper")
@SpringBootApplication
public class HmDianPingApplication {

    /** 应用入口：启动内嵌 Tomcat 并完成 Spring 容器装配。 */
    // SpringApplication.run 内部：创建 ApplicationContext →
    // 执行自动配置与组件扫描 → 启动 Web 服务器 → 应用就绪。
    public static void main(String[] args) {

        SpringApplication.run(HmDianPingApplication.class, args);
    }

}
