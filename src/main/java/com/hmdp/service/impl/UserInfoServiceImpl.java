package com.hmdp.service.impl;

import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserInfoMapper;
import com.hmdp.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 用户详情信息业务的服务实现类。 */
/* 本类为空壳实现：继承 MyBatis-Plus 的 ServiceImpl 后，框架在运行期 */
/* 已按泛型参数提供全部通用能力——第一个泛型 UserInfoMapper 指向 */
/* 数据表 tb_user_info（数据访问层），第二个泛型 UserInfo 是 */
/* 与该表一一映射的实体类。详情查询与资料修改直接复用 */
/* getById、updateById 等父类方法即可，无需额外定制，故类体为空。 */
/* 若后续需要资料完善度计算、字段校验等逻辑，再在类体内扩展方法即可。 */
/* @author jarvis  @since 2026-09-16 */
/* @Service 注解将其注册为 Spring 容器中的单例 Bean，供控制器按接口注入。 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
