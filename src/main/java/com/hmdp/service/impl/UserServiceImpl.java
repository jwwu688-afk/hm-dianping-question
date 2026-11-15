package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * <p>用户服务：验证码登录 / Token 会话 / BitMap 签到，设计说明见方法内注释。</p>
 *
 * @author jarvis
 * @since 2026-09-16
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    // Redis 客户端：验证码、登录 Token、签到位图都存这里
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // —— 验证码防刷设计 ——
    // 短信是真实成本，攻击者刷接口就是刷钱。三层防线：
    // 1) 手机号格式校验：格式不合法直接拒绝，不产生任何下游开销；
    // 2) 验证码 2 分钟 TTL：有效期短，泄露也很快失效；
    // 3) 覆盖写：重复发送以最后一次为准，旧验证码作废。

    /** 发送短信验证码；TTL 与防刷设计见上方与方法内注释。 */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        // RegexUtils 是正则校验工具，不合法的手机号在这里就被挡下
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 格式拦截在发送之前，避免浪费短信成本
        // 3.符合，生成验证码
        // 6 位纯数字验证码，强度与输入成本均衡
        // RandomUtil.randomNumbers 只产生数字，用户在手机上输入成本最低
        String code = RandomUtil.randomNumbers(6);
        // 同一手机号重复发送会覆盖旧验证码，以最后一次为准

        // 4.保存验证码到 redis
        // key 与手机号绑定，TTL 2 分钟后自动失效，兼顾安全与用户体验：
        // 一是过期自动删除省去手工清理；二是有效期短，被拿到也很快失效，防爆破
        // key 形如 login:code:手机号，与 login() 读取时的拼接规则一致
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.发送验证码
        // 演示项目仅记录日志；生产环境应在此接入短信服务商的发送 API
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    // —— 登录态设计：为什么用「随机 Token + Redis Hash」替代 HttpSession ——
    // 1) 集群部署下 session 共享需要粘滞路由或集中存储，成本高；
    // 2) Token 方案无状态：任意节点拿着 Token 查 Redis 即可校验，天然支持水平扩容；
    // 3) TTL 由 Redis 统一管理，到期即登出，配合拦截器续期实现「活跃用户不掉线」。
    // 4) Hash 结构还便于按字段读取：拦截器只取 id/昵称/头像三个字段即可还原用户上下文。
    // 登录与注册在同一方法内完成：调用方无需感知「登录还是注册」，前端流程只有一步。

    /** 短信验证码登录，未注册手机号自动注册；Token 会话设计见上方与方法内注释。 */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.从redis获取验证码并校验
        // 用与发送时相同的 key 取出验证码；key 不存在说明已过期或从未发送
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        // 用户提交的验证码，与缓存中的比对
        String code = loginForm.getCode();
        // 缓存里没有（已过期）或对不上，都按验证码错误处理，不暴露具体原因
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 不一致，报错
            return Result.fail("验证码错误");
        }
        // 校验通过，验证码已完成使命；TTL 到期后自动删除

        // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        // 手机号上有唯一索引，one() 不会命中多条
        User user = query().eq("phone", phone).one();

        // 5.判断用户是否存在
        // 注册兜底：验证码能对上就视为合法用户，未注册直接注册，省去单独的注册流程
        // 这是「验证码即身份证明」的设计：手机号能收到验证码就代表号码归属者本人
        if (user == null) {
            // 6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }

        // 7.保存用户信息到 redis中
        // 7.1.随机生成token，作为登录令牌
        // 随机 UUID 不可预测、不含任何用户信息，比自增 id 安全；
        // toString(true) 表示去掉连字符
        // Token 只出现这一次，之后 Redis 侧的 key 都以它为后缀
        String token = UUID.randomUUID().toString(true);
        // 7.2.将User对象转为HashMap存储
        // 裁剪成 UserDTO：只保留 id/昵称/头像，把手机号等敏感字段排除在 Redis 之外，
        // 同时减小会话体积；业务层拿到的也永远是脱敏后的用户
        // 为什么必须裁剪：Redis 里的会话数据任何拿到 Token 的人都能读，
        // 存完整 User 实体会把手机号泄露给会话持有者
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // ignoreNullValue：空字段不进 Hash；fieldValueEditor：字段值统一转字符串
        // 注意：beanToMap 的第二个参数是目标 Map，这里新建 HashMap 承装结果
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 字段值统一转成字符串，与 Redis Hash 的 String 值类型对齐，避免反序列化歧义
        // 7.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        // 用 Hash 而不是整串 JSON：可以按字段读写（如单独刷新某个字段），粒度更细
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        // TTL 到期即登出；拦截器在命中 Token 时会刷新有效期，实现「活跃用户不掉线」
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8.返回token
        // 前端保存 Token，后续每个请求携带，拦截器据此识别登录态；
        // 调用方无需感知这次是「登录」还是「注册」，两者对前端完全透明
        // session 参数保留但不再使用，接口签名向后兼容
        return Result.ok(token);
    }

    /** 今日签到：把本月位图中「今天」的 bit 置 1；BitMap 设计见方法内注释。 */
    // —— 签到存储设计：Redis BitMap ——
    // 一个月一个 key（sign:用户id:yyyyMM），一天只占 1 个 bit，31 天也才 4 字节；
    // 相比签到表省下海量存储，SETBIT/BITFIELD 原生支持按位读写与统计。
    // 位序约定：bit 0 = 1 号，bit 1 = 2 号……以此类推，全类统一。
    @Override
    public Result sign() {
        // 1.获取当前登录用户
        // 拦截器保证已登录，UserHolder 里一定有用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        // key 形如 sign:用户id:202601，按月分片，单 key 不会无限增长
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        // bit 下标从 0 开始，所以“本月第几天”要减 1；重复签到重复写 1，天然幂等
        // 写入即成功：BitMap 的 set 操作代价极低，不需要返回值确认
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        // 签到成功，无返回数据
        return Result.ok();
    }

    /** 统计本月截至今天的连续签到天数；位图取数与位运算见方法内注释。 */
    // 思路：一次 BITFIELD 取回「1 号到今天」的整段位图（一个无符号整数），
    // 再从最低位（1 号）向高位逐位检查，连续为 1 的个数就是连续签到天数。
    // 为什么不用循环每天查一次 GETBIT：一次 BITFIELD 把 N 天数据拉回内存算，
    // 把 N 次 Redis 往返压缩成 1 次，性能和实现都更简单。
    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        // 与 sign() 写入时的 key 规则完全一致，读同一个月的位图
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        // 取多少个 bit 由它决定：只需要覆盖 1 号到今天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        // GET u{dayOfMonth} 0：从 bit 0 起取 dayOfMonth 个无符号位，恰好覆盖本月 1 号到今天；
        // 一次 Redis 往返取回整段位图，比循环每天 GETBIT 快 N 倍
        // u 表示无符号位段，不会把最高位当符号位
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        // key 不存在或本月从未签到时，bitField 可能返回空
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        // 取回的第一个（也是唯一的）结果就是位图的十进制表示
        Long num = result.get(0);
        // num 为 0 说明本月一个 bit 都没置过 1
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        // 位图低位对应月初日期，从最低位开始数连续的 1 即连续签到天数
        // 例：位图 0b11011（1 号到 5 号）→ 最低位连续 1 有 2 个 → 连续签到 2 天（3 号断签）
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                // 连续签到从今天往回数，遇到第一个 0 就断签了
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            // 无符号右移（>>>）避免最高位被解释成符号位造成死循环
            num >>>= 1;
        }
        // 循环结束，count 即「从今天往前数的连续签到天数」
        // 边界：今天还没签到时，最低位是 0，循环立即结束返回 0
        return Result.ok(count);
    }

    /** 根据手机号创建新用户（注册兜底）；为什么这样注册见方法内注释。 */
    // 注册兜底的用意：验证码已证明手机号归属，无需再设密码填资料，
    // 把注册成本降到最低，资料可以之后再补，避免注册流程劝退用户。
    // 并发说明：手机号有唯一索引，极端并发下重复注册会由数据库约束兜底。
    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        // 手机号是唯一需要持久化的关键信息
        user.setPhone(phone);
        // 昵称加前缀 + 随机串，保证默认昵称不重复且不暴露手机号
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        // 头像等字段留空，用户可后续在个人中心补充
        save(user);
        // 返回新用户：login 方法会继续用它生成 Token 会话
        return user;
    }
}
// —— 本类小结 ——
// sendCode：验证码 2 分钟 TTL，防爆破 + 自动过期；
// login：验证码校验 → 查库 → 注册兜底 → 随机 Token + Hash 写入 Redis；
// sign/signCount：BitMap 按位存储签到记录，一次 BITFIELD 拉回整段位图统计连续天数。
// 会话数据全程脱敏：Redis 中只有 UserDTO 三个字段，不含手机号等敏感信息。
