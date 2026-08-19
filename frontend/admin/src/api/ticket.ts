import http from './http'

/**
 * 工单接口模块。
 *
 * 所有接口均为企业作用域（路径带 /api/enterprises/{enterpriseId}/tickets），
 * 需 X-Enterprise-Id 请求头——由 http.ts 请求拦截器从 localStorage 自动附加，
 * 业务层无需手动处理；未选定企业时不应调用这些接口。
 *
 * 【工单模块与 AI 会话模块的关系】
 * 两者都涉及「AI 回答 + 引用」，但形态不同：
 * - 工单是「企业作用域」资源（挂在企业下，成员协作处理），接口走 axios；
 * - AI 会话是「用户级」资源，且对话是流式 SSE，走 fetch。
 * 工单创建时后端会同步触发一次 AI 自动回答（不是流式），回答落库后随详情返回，
 * 因此工单的接口全部是普通 JSON 请求，用 axios 即可，不需要 fetch 读流。
 */

// ============================================================
// 工单相关类型（与后端 vo 结构一一对应）
// ============================================================

/** 工单分类：固定枚举，保证可统计（与后端 TicketCategory 对应）。 */
export type TicketCategory = 'CONSULTATION' | 'ISSUE' | 'FEEDBACK' | 'OTHER'

/** 工单优先级：LOW/MEDIUM/HIGH/URGENT，影响分配与处理排序。 */
export type TicketPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'

/** 工单状态机：OPEN → ASSIGNED → PROCESSING → RESOLVED → CLOSED，单向流转。 */
export type TicketStatus = 'OPEN' | 'ASSIGNED' | 'PROCESSING' | 'RESOLVED' | 'CLOSED'

/** 工单回复角色：USER（提交人）/ AI（自动回答）/ SUPPORT（客服）。 */
export type TicketReplyRole = 'USER' | 'AI' | 'SUPPORT'

/**
 * 工单响应对象（TicketVO）。
 *
 * 对应后端 TicketVO。assigneeId 为 null 表示「尚未分配客服」。
 * createdAt/updatedAt 为 ISO-8601 UTC 字符串，展示层转本地时间。
 */
export interface TicketVO {
  id: number
  enterpriseId: number
  /** 提交人用户 ID（「我的工单」归属与「提交人视角」判断的依据） */
  userId: number
  category: TicketCategory
  priority: TicketPriority
  status: TicketStatus
  title: string
  description: string
  /** 分配的客服用户 ID；null 表示未分配 */
  assigneeId: number | null
  createdAt: string
  updatedAt: string
}

/**
 * 工单回复响应对象（TicketReplyVO）。
 *
 * 对应后端 TicketReplyVO：
 * - senderId 为 null 时表示该回复无用户身份（即 AI 自动回答）；
 * - citationsJson 是「引用数组」的 JSON 字符串（仅 AI 回复且有知识库依据时有值），
 *   前端用 parseCitationsJson 解析成数组后渲染引用卡片；转人工提示的 AI 回复为 null。
 */
export interface TicketReplyVO {
  id: number
  role: TicketReplyRole
  senderId: number | null
  content: string
  citationsJson: string | null
  createdAt: string
}

/** 工单详情（TicketDetailVO）：工单信息 + 全部回复（升序）。 */
export interface TicketDetailVO {
  ticket: TicketVO
  replies: TicketReplyVO[]
}

/** 创建工单请求参数（title/description/category/priority 均为后端必填项）。 */
export interface CreateTicketParams {
  title: string
  description: string
  category: TicketCategory
  priority: TicketPriority
}

// ============================================================
// 接口函数
// ============================================================

/**
 * 创建工单。
 *
 * POST /api/enterprises/{enterpriseId}/tickets
 * 入参 { title, description, category, priority }。
 * 创建后后端会自动触发一次 AI 自动回答（有知识库依据则回答带引用，无依据则转人工提示），
 * 返回 TicketDetailVO——前端可直接拿到工单 + AI 回复，无需二次请求。
 */
export function createTicket(
  enterpriseId: number,
  params: CreateTicketParams,
): Promise<TicketDetailVO> {
  return http.post(`/enterprises/${enterpriseId}/tickets`, params)
}

/**
 * 我的工单列表。
 *
 * GET /api/enterprises/{enterpriseId}/tickets
 * 返回当前用户在该企业提交的全部工单，按创建时间倒序（最新在前）。
 */
export function listMyTickets(enterpriseId: number): Promise<TicketVO[]> {
  return http.get(`/enterprises/${enterpriseId}/tickets`)
}

/**
 * 工单详情。
 *
 * GET /api/enterprises/{enterpriseId}/tickets/{ticketId}
 * 返回工单 + 全部回复（升序）。可见范围：提交人 / 被分配客服 / 企业 OWNER/ADMIN；
 * 无权访问返回 404。
 */
export function getTicketDetail(
  enterpriseId: number,
  ticketId: number,
): Promise<TicketDetailVO> {
  return http.get(`/enterprises/${enterpriseId}/tickets/${ticketId}`)
}

/**
 * 分配客服。
 *
 * PUT /api/enterprises/{enterpriseId}/tickets/{ticketId}/assign
 * 入参 { assigneeId }。仅企业 OWNER/ADMIN 可操作，工单须处于 OPEN 状态，
 * 成功后状态变为 ASSIGNED。返回更新后的 TicketVO。
 */
export function assignTicket(
  enterpriseId: number,
  ticketId: number,
  assigneeId: number,
): Promise<TicketVO> {
  return http.put(`/enterprises/${enterpriseId}/tickets/${ticketId}/assign`, {
    assigneeId,
  })
}

/**
 * 回复工单。
 *
 * POST /api/enterprises/{enterpriseId}/tickets/{ticketId}/replies
 * 入参 { content }。回复角色由后端按「提交人 / 被分配客服 / 管理员」判定，
 * 客户端不可指定；客服回复若工单处于 OPEN/ASSIGNED，状态自动推进 PROCESSING。
 * 返回完整详情（工单 + 全部回复升序）。
 */
export function addTicketReply(
  enterpriseId: number,
  ticketId: number,
  content: string,
): Promise<TicketDetailVO> {
  return http.post(`/enterprises/${enterpriseId}/tickets/${ticketId}/replies`, {
    content,
  })
}

/**
 * 工单状态流转。
 *
 * PUT /api/enterprises/{enterpriseId}/tickets/{ticketId}/status
 * 入参 { status }，仅接受 PROCESSING / RESOLVED / CLOSED。
 * 后端按状态机 + 操作者身份校验，非法流转返回 409、无权操作返回 403。
 * 返回更新后的 TicketVO。
 */
export function updateTicketStatus(
  enterpriseId: number,
  ticketId: number,
  status: TicketStatus,
): Promise<TicketVO> {
  return http.put(`/enterprises/${enterpriseId}/tickets/${ticketId}/status`, {
    status,
  })
}