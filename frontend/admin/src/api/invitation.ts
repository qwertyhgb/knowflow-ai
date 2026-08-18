import http from './http'
import type { EnterpriseInvitationVO, AcceptInvitationParams } from './types'

/**
 * 被邀请人视角的邀请接口模块。
 *
 * 【为什么独立成 invitation.ts 而不是放 enterprise.ts？】
 * 企业模块的 API 都是企业作用域（需要 X-Enterprise-Id），而这里的接口是
 * "被邀请人视角"的——"我的待处理邀请"和"接受邀请"都不需要企业上下文，
 * 登录即可访问。把不同作用域的接口分开放，职责更清晰：
 * - enterprise.ts：企业作用域（需要选定了企业才能操作）
 * - invitation.ts：用户作用域（登录即可，与企业上下文无关）
 */

/**
 * 获取我的待处理邀请列表。
 *
 * GET /api/invitations/my
 * 非企业作用域，登录即可访问。
 * 仅返回 PENDING 且未过期的邀请。
 * 此接口返回的 enterpriseName 有值（企业名称），用于展示。
 */
export function listMyPendingInvitations(): Promise<EnterpriseInvitationVO[]> {
  return http.get('/invitations/my')
}

/**
 * 接受邀请。
 *
 * POST /api/invitations/accept
 * 入参 { token: string }，token 为 32 位十六进制邀请令牌。
 * 被邀请人通过任意渠道（邮件/IM）获得令牌后，在此接口凭令牌加入企业。
 * 成功返回该企业的 EnterpriseInvitationVO（状态变为 ACCEPTED）。
 */
export function acceptInvitation(
  params: AcceptInvitationParams,
): Promise<EnterpriseInvitationVO> {
  return http.post('/invitations/accept', params)
}