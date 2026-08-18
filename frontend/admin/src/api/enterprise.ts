import http from './http'
import type {
  CreateEnterpriseParams,
  EnterpriseInvitationVO,
  EnterpriseMemberVO,
  EnterpriseVO,
  UpdateEnterpriseParams,
  UpdateMemberStatusParams,
  CreateInvitationParams,
} from './types'

/**
 * 企业接口模块。
 *
 * 【企业作用域与非作用域接口的区别】
 * - 非作用域：创建企业（POST /api/enterprises）、我的企业列表（GET /api/enterprises）。
 *   不带路径企业 ID，不需要 X-Enterprise-Id 请求头。
 * - 作用域：详情、更新、成员、邀请等带 /{enterpriseId} 的路径，必须携带 X-Enterprise-Id，
 *   由 http.ts 请求拦截器从 localStorage 自动附加，业务层无需手动处理。
 */

/**
 * 创建企业。
 *
 * POST /api/enterprises
 * 当前用户自动成为该企业所有者。非作用域接口，无需 X-Enterprise-Id。
 */
export function createEnterprise(params: CreateEnterpriseParams): Promise<EnterpriseVO> {
  return http.post('/enterprises', params)
}

/**
 * 我的企业列表。
 *
 * GET /api/enterprises
 * 返回当前用户加入的全部正常企业数组。非作用域接口，无需 X-Enterprise-Id。
 */
export function getMyEnterprises(): Promise<EnterpriseVO[]> {
  return http.get('/enterprises')
}

/**
 * 企业详情。
 *
 * GET /api/enterprises/{enterpriseId}
 * 企业作用域接口，请求拦截器会自动带上 X-Enterprise-Id。
 */
export function getEnterpriseDetail(enterpriseId: number): Promise<EnterpriseVO> {
  return http.get(`/enterprises/${enterpriseId}`)
}

/**
 * 更新企业名称。
 *
 * PUT /api/enterprises/{enterpriseId}
 * 企业作用域接口，请求拦截器会自动带上 X-Enterprise-Id。
 */
export function updateEnterprise(
  enterpriseId: number,
  params: UpdateEnterpriseParams,
): Promise<EnterpriseVO> {
  return http.put(`/enterprises/${enterpriseId}`, params)
}

// ============================================================
// 成员管理接口（企业作用域）
// ============================================================

/**
 * 获取企业成员列表。
 *
 * GET /api/enterprises/{enterpriseId}/members
 * 企业作用域接口，请求拦截器自动附加 X-Enterprise-Id。
 * 返回当前企业所有成员，包含角色、状态等信息。
 */
export function getMembers(enterpriseId: number): Promise<EnterpriseMemberVO[]> {
  return http.get(`/enterprises/${enterpriseId}/members`)
}

/**
 * 移除企业成员。
 *
 * POST /api/enterprises/{enterpriseId}/members/{targetUserId}/remove
 * 企业作用域接口。注意路径参数是 targetUserId（用户 ID），不是成员关系 ID。
 * 不可移除 OWNER，不可移除自己（需调用 leave 退出）。
 * 权限不足返回 403。
 */
export function removeMember(
  enterpriseId: number,
  targetUserId: number,
): Promise<void> {
  return http.post(`/enterprises/${enterpriseId}/members/${targetUserId}/remove`)
}

/**
 * 修改成员状态（禁用/恢复）。
 *
 * PUT /api/enterprises/{enterpriseId}/members/{targetUserId}/status
 * 入参 { status: "NORMAL" | "DISABLED" }。
 * NORMAL 恢复，DISABLED 禁用。
 * 不可操作 OWNER，不可操作自己。
 */
export function updateMemberStatus(
  enterpriseId: number,
  targetUserId: number,
  params: UpdateMemberStatusParams,
): Promise<void> {
  return http.put(`/enterprises/${enterpriseId}/members/${targetUserId}/status`, params)
}

/**
 * 主动退出当前企业。
 *
 * POST /api/enterprises/{enterpriseId}/members/leave
 * 与"移除"不同，这是当前登录用户主动退出。
 * 退出成功后前端应清除企业上下文。
 */
export function leaveEnterprise(enterpriseId: number): Promise<void> {
  return http.post(`/enterprises/${enterpriseId}/members/leave`)
}

// ============================================================
// 邀请管理接口（企业作用域）
// ============================================================

/**
 * 创建邀请。
 *
 * POST /api/enterprises/{enterpriseId}/invitations
 * 入参 { email, role }，role 可选 MEMBER 或 ADMIN（不能传 OWNER）。
 * 成功响应会带 token 字段（32 位十六进制明文令牌，仅此一次返回，之后无法找回）。
 */
export function createInvitation(
  enterpriseId: number,
  params: CreateInvitationParams,
): Promise<EnterpriseInvitationVO> {
  return http.post(`/enterprises/${enterpriseId}/invitations`, params)
}

/**
 * 获取企业邀请列表。
 *
 * GET /api/enterprises/{enterpriseId}/invitations
 * 返回该企业所有邀请记录，包含已接受、已撤销、已过期的。
 * 列表接口返回的 token 字段为 null（安全考虑，仅创建时返回一次）。
 */
export function listInvitations(enterpriseId: number): Promise<EnterpriseInvitationVO[]> {
  return http.get(`/enterprises/${enterpriseId}/invitations`)
}

/**
 * 撤销邀请。
 *
 * POST /api/enterprises/{enterpriseId}/invitations/{invitationId}/revoke
 * 仅 PENDING 状态的邀请可撤销，撤销后状态变为 REVOKED（终态不可逆）。
 */
export function revokeInvitation(
  enterpriseId: number,
  invitationId: number,
): Promise<void> {
  return http.post(`/enterprises/${enterpriseId}/invitations/${invitationId}/revoke`)
}