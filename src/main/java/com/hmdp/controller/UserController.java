package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author jarvis
 */
// ==================== 用户业务域控制器（路由前缀 /user） ====================
// 职责：短信验证码发送与登录校验、登出、获取当前登录用户、
// 用户公开信息/详情查询、每日签到与签到统计。
// 登录态由拦截器链维护：登录成功后用户信息写入 Redis（token 为 key），
// 请求经 RefreshTokenInterceptor 还原到 ThreadLocal 并续期；
// 签到功能基于 Redis BitMap 实现（按月一个 key，日期为位偏移）。
// /user/code、/user/login 在登录白名单中，其余接口需要登录。
// 返回值统一为 Result 结构；UserDTO 只暴露 id/nickName/icon 三个字段。
// 手机号等敏感信息不出现在任何接口响应中。
// 短信验证码当前为模拟发送，接入真实短信网关只需改 UserService。
// 登录态过期时间为 30 分钟，由 RefreshTokenInterceptor 每次请求续期。
// 签到 BitMap 的 key 按月分片，历史月份可长期保留用于统计。
// info 与 queryUserById 是两个独立接口：前者查扩展表，后者查主表。
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    // 接口：发送短信验证码 POST /user/code?phone=手机号。
    // 业务语义：校验手机号格式后生成 6 位验证码，保存到 Session
    // 并（模拟）发送短信；验证码有效期与防刷逻辑由 UserService 实现。
    // 该接口在登录白名单中，可匿名访问。
    // Session 用于暂存验证码，登录时与用户输入比对。
    // phone 参数未做服务端正则校验时由 Service 内校验，格式错误返回失败。
    /**
     * 发送手机验证码
     */
    @PostMapping("/code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 发送短信验证码并保存验证码
        return userService.sendCode(phone, session);
    }

    // 接口：登录 POST /user/login（JSON 请求体 LoginFormDTO）。
    // 业务语义：验证码登录时校验手机号与验证码匹配；
    // 新用户自动注册。登录成功后生成随机 token，
    // 用户信息以 JSON 写入 Redis（key=user:login:{token}，TTL 30 分钟起），
    // token 返回给前端；后续请求由 RefreshTokenInterceptor 校验并续期。
    // 登录接口本身也在白名单中；已登录用户重复登录会覆盖旧 token。
    // 密码登录分支预留：当前项目以验证码登录为主。
    // 登录成功后 Session 中不再保留验证码，防止重放。
    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // 实现登录功能
        return userService.login(loginForm, session);
    }

    // 接口：登出 POST /user/logout。
    // 当前为占位实现：直接返回"功能未完成"。
    // 正确做法：删除 Redis 中的登录缓存（user:login:{token}）
    // 并清理 ThreadLocal，防止 token 继续可用。
    // 登出接口需要登录才能调用（不在白名单中）。
    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(){
        // TODO 实现登出功能
        return Result.fail("功能未完成");
    }

    /** 获取当前登录用户信息：GET /user/me。 */
    // 业务语义：从 ThreadLocal（UserHolder）直接读取，无需查库；
    // 未登录请求已被 LoginInterceptor 以 401 拦截，不会走到这里。
    // 返回 UserDTO（id、nickName、icon），不含手机号等敏感字段。
    // @return 当前登录用户的 UserDTO
    // 高频接口：首页等页面每次加载都会调用，纯内存读取无 IO。
    // 若未登录却被放行到此处（配置错误），UserHolder.getUser() 会返回 null。
    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    /** 查询用户详情页扩展信息：GET /user/info/{id}。 */
    // 业务语义：tb_user_info 与主表 tb_user 分离，存放城市、intro 等
    // 扩展字段；首次查看可能尚无记录，此时返回空数据（Result.ok()）；
    // createTime/updateTime 置 null，时间字段不外露给前端。
    // 主键 getById 直查数据库，无缓存。
    // 详情页由本人或他人查看均可，接口不校验归属。
    // 空详情返回 Result.ok()（data 为 null），前端按"暂无详情"渲染。
    // @param userId 用户 id
    // @return 用户扩展详情（可为空）
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        // getById 为主键等值查询，走主键索引
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    /** 按 id 查询用户公开信息：GET /user/{id}。 */
    // 业务语义：用户不存在时返回空数据；
    // 存在则裁剪为 UserDTO（隐藏手机号、密码等敏感字段），
    // 用于笔记详情页等场景展示作者信息。
    // BeanUtil.copyProperties 按字段名复制，其余字段自动丢弃。
    // @param userId 用户 id
    // @return 用户公开信息 UserDTO
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        // 用户不存在时返回空 data，前端隐藏作者信息区域
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    /** 今日签到：POST /user/sign。 */
    // 业务语义：以 Redis BitMap 记录签到
    // （key=sing:uid:{用户id}:{年月}，位偏移为当日日期-1）；
    // 同一天重复签到不会重复置位，委托 UserService.sign 实现。
    // @return 本次签到结果
    // 需要登录：当前用户来自 ThreadLocal，接口无参数。
    // 签到数据用于日历展示与连续签到统计。
    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    /** 查询签到统计：GET /user/sign/count。 */
    // 业务语义：基于 BitMap 统计连续签到次数与本月累计签到天数，
    // 具体统计口径由 UserService.signCount 实现。
    // @return 签到统计结果
    // 与 /user/sign 配套使用，进入签到页时调用一次。
    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }
}
