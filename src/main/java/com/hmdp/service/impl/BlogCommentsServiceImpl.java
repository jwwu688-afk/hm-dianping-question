package com.hmdp.service.impl;

import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 博客评论业务的服务实现类。 */
/* 本类为空壳实现：继承 MyBatis-Plus 的 ServiceImpl 后，框架在运行期 */
/* 已按泛型参数提供全部通用能力——第一个泛型 BlogCommentsMapper 指向 */
/* 数据表 tb_blog_comments（数据访问层），第二个泛型 BlogComments 是 */
/* 与该表一一映射的实体类。save、page、count、lambdaQuery 等方法 */
/* 因此开箱即用，评论的业务逻辑简单到无需额外定制，故类体为空。 */
/* 若后续需要定制造箱、楼层折叠等逻辑，再在类体内覆写或新增方法即可。 */
/* @author jarvis  @since 2026-09-16 */
/* @Service 注解将其注册为 Spring 容器中的单例 Bean，供控制器按接口注入。 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
