package io.github.qwertyhgb.knowflow.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.user.dto.request.UserLoginRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserProfileUpdateRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserRegisterRequest;
import io.github.qwertyhgb.knowflow.user.entity.User;
import io.github.qwertyhgb.knowflow.user.enums.UserStatus;
import io.github.qwertyhgb.knowflow.user.mapper.UserMapper;
import io.github.qwertyhgb.knowflow.user.service.UserService;
import io.github.qwertyhgb.knowflow.user.vo.UserLoginVO;
import io.github.qwertyhgb.knowflow.user.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * 用户业务服务实现，承载注册、登录、查询、资料修改与登出的核心业务规则。
 *
 * <p><strong>关键设计原则：</strong></p>
 * <ul>
 *   <li><strong>密码安全</strong>：明文密码仅在方法内部短暂存在，入库前必须经
 *       {@link PasswordEncoder} 转为 BCrypt 哈希，任何路径都不允许明文落库。</li>
 *   <li><strong>账号不可枚举</strong>：登录时「邮箱不存在」与「密码错误」统一返回
 *       {@link ErrorCode#INVALID_CREDENTIALS}，避免攻击者通过错误差异探测账号是否存在。</li>
 *   <li><strong>邮箱唯一</strong>：注册前先查询邮箱是否存在，数据库唯一索引继续作为最终约束。</li>
 *   <li><strong>统一 UTC 时间</strong>：所有时间字段通过可注入的 {@link Clock} 获取，
 *       保证全链路 UTC、可测试冻结时间，不依赖 JVM 默认时区。</li>
 *   <li><strong>日志纪律</strong>：只记录系统生成的标识（如 userId），
 *       不记录邮箱、明文密码等个人可识别信息；事件名统一 {@code snake_case}。</li>
 * </ul>
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    /**
     * MyBatis-Plus 基础 Mapper，负责 {@code sys_user} 表的增删改查。
     *
     * <p>业务层只依赖 Mapper 接口，不直接编写 SQL；
     * 密码哈希、状态等字段由本类在内存中组装好后交给 Mapper 持久化。</p>
     */
    private final UserMapper userMapper;

    /**
     * BCrypt 密码编码器。
     *
     * <p>注册时用 {@link PasswordEncoder#encode(CharSequence)} 哈希明文密码；
     * 登录时用 {@link PasswordEncoder#matches(CharSequence, String)} 校验。
     * 实现由 {@code PasswordConfig} 提供（{@code BCryptPasswordEncoder}），
     * 业务层只依赖接口便于测试替换。</p>
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * 可注入的 UTC 时钟。
     *
     * <p>用于生成 {@code createdAt} / {@code updatedAt}：
     * 生产环境注入 {@code Clock.systemUTC()} 保证统一 UTC；
     * 测试环境注入 {@code Clock.fixed(...)} 冻结时间，使时间断言精确可控。
     * 禁止直接调用 {@code Instant.now()}（会依赖 JVM 默认时区且无法测试）。</p>
     */
    private final Clock clock;

    /**
     * 登录态 Token 管理。
     *
     * <p>登录成功后签发 Token 并写入 Redis 登录态（{@code auth:token:{token}} → userId），
     * 登出时使 Token 失效，是后续请求认证（{@code TokenAuthenticationFilter}）的依据。</p>
     */
    private final TokenService tokenService;

    /**
     * 构造器注入全部依赖（Spring 自动装配）。
     *
     * <p>显式构造器注入而非字段注入：依赖以 {@code final} 声明保证不可变，
     * 便于单元测试直接构造实例并替换任一依赖（如 mock {@link Clock} 冻结时间）。</p>
     *
     * @param userMapper     用户表 Mapper
     * @param passwordEncoder 密码编码器
     * @param clock           UTC 时钟
     * @param tokenService    登录态 Token 管理
     */
    public UserServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder, Clock clock,
                           TokenService tokenService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.tokenService = tokenService;
    }

    /**
     * 注册新用户。
     *
     * <p><strong>处理流程：</strong></p>
     * <ol>
     *   <li>邮箱归一化（去首尾空白 + 转小写），保证与唯一索引存储口径一致，避免大小写变体绕过唯一性。</li>
     *   <li>昵称去首尾空白。</li>
     *   <li>邮箱存在性预检查（快速失败，避免无谓的哈希计算）。</li>
     *   <li>组装 {@link User} 实体：密码哈希、默认状态 {@code NORMAL}、统一 UTC 时间。</li>
     *   <li>执行 insert，把用户保存到数据库。</li>
     * </ol>
     *
     * @param request 注册请求参数（已通过 {@code @Valid} 校验非空与格式）
     * @return 注册成功后的用户视图（不包含密码哈希等敏感字段）
     * @throws BusinessException 邮箱已注册时抛出 {@link ErrorCode#EMAIL_ALREADY_EXISTS}
     */
    @Override
    public UserVO register(UserRegisterRequest request) {
        // 归一化邮箱：统一小写，避免 "User@Example.com" 与 "user@example.com" 被视为不同账号。
        String email = normalizeEmail(request.getEmail());
        // 昵称去首尾空白，避免用户误输前后空格导致展示不一致。
        String nickname = request.getNickname().strip();
        // 学习版先用一次 exists 查询处理普通的重复注册；数据库唯一索引仍会阻止重复数据落库。
        if (userMapper.exists(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email))) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 统一取一次时间，保证 createdAt 与 updatedAt 精确一致。
        Instant now = clock.instant();
        User user = new User();
        user.setEmail(email);
        // 只保存密码哈希，绝不保存明文密码。
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(nickname);
        // 新注册账号默认正常状态。
        user.setStatus(UserStatus.NORMAL);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        userMapper.insert(user);

        // 日志只记录系统生成的 userId，不记录邮箱（个人可识别信息）与任何密码相关数据。
        log.info("event=user_registered userId={}", user.getId());
        // 通过 VO 转换隐藏密码哈希等敏感字段，Entity 不直接返回给前端。
        return UserVO.from(user);
    }

    /**
     * 用户登录：校验凭证并签发登录态 Token。
     *
     * <p><strong>处理流程：</strong></p>
     * <ol>
     *   <li>邮箱归一化。</li>
     *   <li>按邮箱查用户，校验密码 ——「邮箱不存在」与「密码错误」统一返回
     *       {@link ErrorCode#INVALID_CREDENTIALS}，防止账号枚举攻击。</li>
     *   <li>校验账号状态，禁用账号拒绝登录。</li>
     *   <li>登录成功：通过 {@link TokenService} 签发 Token 并保存 Redis 登录态。</li>
     * </ol>
     *
     * <p><strong>校验顺序说明：</strong>先校验密码、再校验状态。
     * 若先校验状态，攻击者可区分「禁用账号」与「密码错误」，从而探测邮箱是否真实存在。</p>
     *
     * @param request 登录请求参数
     * @return 登录成功后的 Token 与用户视图
     * @throws BusinessException 凭证错误抛 {@link ErrorCode#INVALID_CREDENTIALS}；
     *                           账号禁用抛 {@link ErrorCode#USER_DISABLED}
     */
    @Override
    public UserLoginVO login(UserLoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        String password = request.getPassword();

        // email 有唯一索引，selectOne 最多返回一条；不存在时返回 null。
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        // 邮箱不存在与密码错误统一返回同一错误码，避免泄露账号是否存在。
        // 注意 || 短路：user 为 null 时不会执行 matches，也无需担心空指针。
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 凭证正确后再检查状态，避免泄露「禁用账号的密码是否正确」。
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        // 签发 Token 并写入 Redis 登录态，供后续请求经 TokenAuthenticationFilter 认证。
        String token = tokenService.createToken(user.getId());
        log.info("event=user_logged_in userId={}", user.getId());
        return UserLoginVO.of(token, UserVO.from(user));
    }

    /**
     * 按主键查询用户。
     *
     * @param id 用户 ID（来自当前登录态，如 {@code /api/users/me} 接口）
     * @return 用户视图
     * @throws BusinessException 用户不存在时抛 {@link ErrorCode#NOT_FOUND}
     */
    @Override
    public UserVO getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return UserVO.from(user);
    }

    /**
     * 修改当前用户资料，目前仅支持修改昵称。
     *
     * <p>昵称统一去除首尾空白；仅构造包含主键、昵称和更新时间的局部实体进行更新，
     * 避免把查询出的密码哈希、账号状态等字段意外回写。归一化后的昵称未变化时不更新数据库。</p>
     *
     * @param userId  当前登录用户 ID
     * @param request 资料修改请求参数
     * @return 修改后的用户视图
     * @throws BusinessException 用户不存在时抛 {@link ErrorCode#NOT_FOUND}
     */
    @Override
    public UserVO updateProfile(Long userId, UserProfileUpdateRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        String nickname = request.getNickname().strip();
        if (nickname.equals(user.getNickname())) {
            return UserVO.from(user);
        }

        Instant updatedAt = clock.instant();
        User update = new User();
        update.setId(userId);
        update.setNickname(nickname);
        update.setUpdatedAt(updatedAt);

        int updatedRows = userMapper.updateById(update);
        if (updatedRows != 1) {
            throw new IllegalStateException("Expected one updated user row, but got " + updatedRows);
        }

        user.setNickname(nickname);
        user.setUpdatedAt(updatedAt);
        log.info("event=user_profile_updated userId={}", userId);
        return UserVO.from(user);
    }

    /**
     * 用户登出：使指定 Token 失效。
     *
     * <p>调用 {@link TokenService#revokeToken(String)} 删除 Redis 中的登录态记录，
     * 之后该 Token 将无法通过认证。Redis 键带有 TTL，即使未主动登出也会自然过期。</p>
     *
     * @param userId 当前登录用户 ID（仅用于日志，便于追踪登出行为）
     * @param token  待失效的 Token
     */
    @Override
    public void logout(Long userId, String token) {
        tokenService.revokeToken(token);
        log.info("event=user_logged_out userId={}", userId);
    }

    /**
     * 归一化邮箱：去首尾空白并转小写。
     *
     * <p>使用 {@link Locale#ROOT} 而非默认语言环境，避免「土耳其语环境下
     * {@code I.toLowerCase()} 变成带点的 ı」这类区域性大小写问题，
     * 保证邮箱归一化在不同地区的结果完全一致。</p>
     *
     * @param email 原始邮箱输入
     * @return 归一化后的邮箱（全小写、无首尾空白）
     */
    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

}
