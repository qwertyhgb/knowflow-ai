package io.github.qwertyhgb.knowflow.auth.context;

import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;

/**
 * 认证主体：当前登录用户 + 当前企业上下文。
 *
 * <p><strong>生命周期：</strong></p>
 * <ol>
 *   <li>{@code TokenAuthenticationFilter} 认证成功后先建立<strong>无企业上下文</strong>的主体
 *       （{@link #withoutEnterprise(Long)}，仅有 {@code userId}）；</li>
 *   <li>请求若命中企业作用域路径（{@code /api/enterprises/{enterpriseId}/**}）并携带
 *       {@code X-Enterprise-Id} 请求头，{@code EnterpriseContextFilter} 校验成员身份后
 *       用 {@link #withEnterprise(Long, EnterpriseMemberRole)} 重建主体，
 *       填入当前企业 ID 与成员角色。</li>
 * </ol>
 *
 * <p>使用 record 定义：认证主体是不可变值对象，record 天然保证字段 final、
 * 访问器简洁（{@code user.userId()}），是 Java 21 下这类对象的标准写法。</p>
 */
public record EnterpriseUser(
        /** 当前登录用户 ID，认证成功后始终非空。 */
        Long userId,
        /** 当前企业 ID；未携带或未通过企业上下文校验时为 null。 */
        Long currentEnterpriseId,
        /** 当前企业在该企业的成员角色；与 {@code currentEnterpriseId} 同时为 null 或同时非空。 */
        EnterpriseMemberRole currentRole) {

    /** 构造仅含用户身份、无企业上下文的主体（Token 认证刚完成时的状态）。 */
    public static EnterpriseUser withoutEnterprise(Long userId) {
        return new EnterpriseUser(userId, null, null);
    }

    /** 基于当前主体派生带企业上下文的新主体（原主体不可变，不做就地修改）。 */
    public EnterpriseUser withEnterprise(Long enterpriseId, EnterpriseMemberRole role) {
        return new EnterpriseUser(userId, enterpriseId, role);
    }
}
