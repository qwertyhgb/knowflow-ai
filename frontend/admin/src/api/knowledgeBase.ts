import http from './http'
import type {
  KnowledgeBaseVO,
  KnowledgeBaseMemberVO,
  CreateKnowledgeBaseParams,
  UpdateKnowledgeBaseParams,
  AddKnowledgeBaseMemberParams,
  UpdateMemberRoleParams,
} from './types'

/**
 * 知识库接口模块。
 *
 * 所有接口均为企业作用域（路径带 /enterprises/{enterpriseId}），
 * 请求拦截器会自动附加 X-Enterprise-Id 请求头。
 * 知识库从属于当前企业，不改变企业上下文。
 */

// ============================================================
// 知识库 CRUD
// ============================================================

/**
 * 创建知识库。
 *
 * POST /api/enterprises/{enterpriseId}/knowledge-bases
 * 仅需企业正常成员身份即可创建。
 * 创建成功后返回的 myRole 为 null（因为成员关系尚未建立）。
 */
export function createKnowledgeBase(
  enterpriseId: number,
  params: CreateKnowledgeBaseParams,
): Promise<KnowledgeBaseVO> {
  return http.post(`/enterprises/${enterpriseId}/knowledge-bases`, params)
}

/**
 * 获取知识库列表。
 *
 * GET /api/enterprises/{enterpriseId}/knowledge-bases
 * 可见性规则：PRIVATE 仅知识库成员可见，PUBLIC 企业内所有正常成员可见。
 * 返回的每个元素带 myRole 字段，用于判断当前用户在知识库中的角色。
 */
export function getKnowledgeBases(
  enterpriseId: number,
): Promise<KnowledgeBaseVO[]> {
  return http.get(`/enterprises/${enterpriseId}/knowledge-bases`)
}

/**
 * 获取知识库详情。
 *
 * GET /api/enterprises/{enterpriseId}/knowledge-bases/{knowledgeBaseId}
 */
export function getKnowledgeBaseDetail(
  enterpriseId: number,
  knowledgeBaseId: number,
): Promise<KnowledgeBaseVO> {
  return http.get(`/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}`)
}

/**
 * 更新知识库。
 *
 * PUT /api/enterprises/{enterpriseId}/knowledge-bases/{knowledgeBaseId}
 * 全量更新语义：必须传 name、description、accessMode 全部字段。
 * 需要知识库 ADMIN 角色或企业 OWNER/ADMIN 权限。
 */
export function updateKnowledgeBase(
  enterpriseId: number,
  knowledgeBaseId: number,
  params: UpdateKnowledgeBaseParams,
): Promise<KnowledgeBaseVO> {
  return http.put(`/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}`, params)
}

/**
 * 删除知识库。
 *
 * DELETE /api/enterprises/{enterpriseId}/knowledge-bases/{knowledgeBaseId}
 * 级联删除其全部成员记录。
 * 需要知识库 ADMIN 角色或企业 OWNER/ADMIN 权限。
 */
export function deleteKnowledgeBase(
  enterpriseId: number,
  knowledgeBaseId: number,
): Promise<void> {
  return http.delete(`/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}`)
}

/**
 * 修改知识库状态（禁用/启用）。
 *
 * PUT /api/enterprises/{enterpriseId}/knowledge-bases/{knowledgeBaseId}/status
 * 入参 { status: "NORMAL" | "DISABLED" }。
 * 需要知识库 ADMIN 角色或企业 OWNER/ADMIN 权限。
 */
export function updateKnowledgeBaseStatus(
  enterpriseId: number,
  knowledgeBaseId: number,
  params: { status: 'NORMAL' | 'DISABLED' },
): Promise<KnowledgeBaseVO> {
  return http.put(
    `/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}/status`,
    params,
  )
}

// ============================================================
// 知识库成员管理
// ============================================================

/**
 * 获取知识库成员列表。
 *
 * GET /api/enterprises/{enterpriseId}/knowledge-bases/{knowledgeBaseId}/members
 * 返回的 KnowledgeBaseMemberVO 不包含用户昵称/邮箱，
 * 前端需配合 getMembers（企业成员列表）做 userId 映射。
 */
export function getKnowledgeBaseMembers(
  enterpriseId: number,
  knowledgeBaseId: number,
): Promise<KnowledgeBaseMemberVO[]> {
  return http.get(
    `/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}/members`,
  )
}

/**
 * 添加知识库成员。
 *
 * POST /api/enterprises/{enterpriseId}/knowledge-bases/{knowledgeBaseId}/members
 * 入参 { userId, memberRole }。
 * 被添加的用户必须是企业成员（后端会校验）。
 * 需要知识库 ADMIN 角色或企业 OWNER/ADMIN 权限。
 */
export function addKnowledgeBaseMember(
  enterpriseId: number,
  knowledgeBaseId: number,
  params: AddKnowledgeBaseMemberParams,
): Promise<KnowledgeBaseMemberVO> {
  return http.post(
    `/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}/members`,
    params,
  )
}

/**
 * 修改知识库成员角色。
 *
 * PUT /api/enterprises/{enterpriseId}/knowledge-bases/{knowledgeBaseId}/members/{targetUserId}/role
 * 入参 { memberRole }。
 * 需要知识库 ADMIN 角色或企业 OWNER/ADMIN 权限。
 */
export function updateKnowledgeBaseMemberRole(
  enterpriseId: number,
  knowledgeBaseId: number,
  targetUserId: number,
  params: UpdateMemberRoleParams,
): Promise<void> {
  return http.put(
    `/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}/members/${targetUserId}/role`,
    params,
  )
}

/**
 * 移除知识库成员。
 *
 * DELETE /api/enterprises/{enterpriseId}/knowledge-bases/{knowledgeBaseId}/members/{targetUserId}
 * 需要知识库 ADMIN 角色或企业 OWNER/ADMIN 权限。
 */
export function removeKnowledgeBaseMember(
  enterpriseId: number,
  knowledgeBaseId: number,
  targetUserId: number,
): Promise<void> {
  return http.delete(
    `/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}/members/${targetUserId}`,
  )
}