/**
 * 前端 API 类型定义。
 *
 * 与后端约定的结构一一对应，保证类型安全。
 * 后端统一响应格式：{ code: string, message: string, data: T | null }
 * 成功时 code 为 "SUCCESS"。
 */

// ============================================================
// 通用响应类型
// ============================================================

/**
 * 后端统一响应包装。
 *
 * T 是 data 的类型，成功时有值，失败时为 null。
 * 前端 axios 拦截器会先解包一层：非 SUCCESS 抛出异常，
 * 业务代码只处理 data 即可。
 */
export interface Result<T = unknown> {
  /** 业务错误码，成功为 "SUCCESS"，失败如 "UNAUTHORIZED"、"INVALID_CREDENTIALS" 等 */
  code: string
  /** 人类可读的提示信息 */
  message: string
  /** 业务数据，成功时非 null，失败时 null */
  data: T | null
}

// ============================================================
// 用户相关
// ============================================================

/**
 * 用户账号状态枚举。
 *
 * 后端 UserStatus 枚举的 Jackson 序列化值为枚举名称（NORMAL / DISABLED），
 * 不是数字 1 / 0，所以前端用字符串联合类型。
 */
export type UserStatus = 'NORMAL' | 'DISABLED'

/**
 * 用户响应对象（UserVO）。
 *
 * 对应后端 io.github.qwertyhgb.knowflow.user.vo.UserVO。
 * 不包含 passwordHash，时间字段为 ISO-8601 UTC 字符串。
 */
export interface UserVO {
  id: number
  email: string
  nickname: string
  status: UserStatus
  /** ISO-8601 UTC 字符串，如 "2026-08-13T07:30:25Z" */
  createdAt: string
  /** ISO-8601 UTC 字符串 */
  updatedAt: string
}

/**
 * 登录成功响应（UserLoginVO）。
 *
 * 对应后端 io.github.qwertyhgb.knowflow.user.vo.UserLoginVO。
 * 包含 token 和当前用户信息，token 需要存入 localStorage 并用于后续请求头。
 */
export interface LoginResult {
  token: string
  user: UserVO
}

// ============================================================
// 请求参数类型
// ============================================================

/** 登录请求参数 */
export interface LoginParams {
  email: string
  password: string
}

/** 注册请求参数 */
export interface RegisterParams {
  email: string
  password: string
  nickname: string
}

// ============================================================
// 企业相关
// ============================================================

/**
 * 企业状态枚举。
 *
 * 对应后端 EnterpriseStatus，Jackson 序列化值为枚举名称（NORMAL / DISABLED）。
 */
export type EnterpriseStatus = 'NORMAL' | 'DISABLED'

/**
 * 企业响应对象（EnterpriseVO）。
 *
 * 对应后端 io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseVO。
 * createdAt 为 ISO-8601 UTC 字符串，展示层转本地时间，业务层保持字符串原样。
 */
export interface EnterpriseVO {
  id: number
  name: string
  status: EnterpriseStatus
  /** ISO-8601 UTC 字符串 */
  createdAt: string
}

/** 创建企业请求参数：仅 name 必填，长度 ≤100（与后端校验一致） */
export interface CreateEnterpriseParams {
  name: string
}

/** 更新企业请求参数 */
export interface UpdateEnterpriseParams {
  name: string
}

// ============================================================
// 企业成员相关
// ============================================================

/**
 * 企业成员状态枚举。
 *
 * 对应后端 EnterpriseMemberStatus 枚举，Jackson 序列化值为枚举名称。
 */
export type EnterpriseMemberStatus = 'NORMAL' | 'DISABLED'

/**
 * 企业成员角色枚举。
 *
 * 对应后端 EnterpriseMemberRole 枚举。
 * OWNER（所有者）是创建企业时自动授予的，不可通过邀请获得；
 * ADMIN（管理员）和 MEMBER（普通成员）可通过邀请授予。
 */
export type EnterpriseMemberRole = 'OWNER' | 'ADMIN' | 'MEMBER'

/**
 * 企业成员响应对象（EnterpriseMemberVO）。
 *
 * 对应后端 io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseMemberVO。
 * 注意：这里的 id 是成员关系 ID（EnterpriseMember 的主键），不是用户 ID（userId）。
 * email 和 nickname 可能为 null（比如用户被删除后，关联信息可能丢失）。
 * roleCode 理论上不会为 null，但保留 null 类型以应对极端情况。
 * joinedAt 为 ISO-8601 UTC 字符串。
 */
export interface EnterpriseMemberVO {
  /** 成员关系 ID（EnterpriseMember 主键） */
  id: number
  /** 用户 ID */
  userId: number
  /** 用户邮箱，可能为 null */
  email: string | null
  /** 用户昵称，可能为 null */
  nickname: string | null
  /** 角色编码：OWNER / ADMIN / MEMBER */
  roleCode: EnterpriseMemberRole | null
  /** 成员状态：NORMAL / DISABLED */
  status: EnterpriseMemberStatus
  /** ISO-8601 UTC 字符串，加入企业时间 */
  joinedAt: string
}

/** 更新成员状态请求参数 */
export interface UpdateMemberStatusParams {
  status: EnterpriseMemberStatus
}

// ============================================================
// 企业邀请相关
// ============================================================

/**
 * 邀请状态枚举。
 *
 * 对应后端 EnterpriseInvitationStatus 枚举。
 * 邀请状态机：PENDING（待接受）→ ACCEPTED（已接受）/ REVOKED（已撤销）/ EXPIRED（已过期），
 * 三个终态不可逆。
 */
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED'

/**
 * 邀请角色枚举。
 *
 * 对应后端 EnterpriseInvitationRole 枚举。
 * 创建邀请时只能传 MEMBER 或 ADMIN，不能传 OWNER（OWNER 是创建者专属角色）。
 */
export type InvitationRole = 'ADMIN' | 'MEMBER'

/**
 * 企业邀请响应对象（EnterpriseInvitationVO）。
 *
 * 对应后端 io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseInvitationVO。
 * 关键语义：
 * - token 仅在创建邀请成功时返回（32 位十六进制明文），之后通过列表接口获取时为 null，
 *   因为系统只保存 SHA-256 哈希，明文无法找回。
 * - enterpriseName 在"我的邀请列表"接口有值，在"企业邀请列表"接口为 null（因为企业名称已知）。
 * - acceptedAt 为 null 表示尚未接受。
 */
export interface EnterpriseInvitationVO {
  id: number
  enterpriseId: number
  /** 企业名称。我的邀请列表有值，企业邀请列表为 null */
  enterpriseName: string | null
  /** 被邀请人邮箱 */
  inviteeEmail: string
  /** 授予角色：ADMIN / MEMBER */
  role: InvitationRole
  /** 邀请状态：PENDING / ACCEPTED / REVOKED / EXPIRED */
  status: InvitationStatus
  /** ISO-8601 UTC 字符串，过期时间 */
  expiresAt: string
  /** ISO-8601 UTC 字符串，创建时间 */
  createdAt: string
  /** ISO-8601 UTC 字符串，接受时间；null 表示尚未接受 */
  acceptedAt: string | null
  /** 邀请令牌。仅创建时返回，列表接口为 null */
  token: string | null
}

/** 创建邀请请求参数 */
export interface CreateInvitationParams {
  /** 被邀请人邮箱，必填，邮箱格式，≤254 字符 */
  email: string
  /** 授予角色：MEMBER 或 ADMIN，不能传 OWNER */
  role: InvitationRole
}

/** 接受邀请请求参数 */
export interface AcceptInvitationParams {
  /** 32 位十六进制邀请令牌 */
  token: string
}

// ============================================================
// 文档相关
// ============================================================

/**
 * 文档状态枚举。
 *
 * 状态机语义：
 * - UPLOADED：已上传，待解析（初始状态，后端上传成功后自动入队解析）
 * - PARSING：解析中（异步任务执行中）
 * - READY：解析完成，可检索（终态）
 * - FAILED：解析失败（终态不可恢复，需重新上传）
 *
 * 流转路径：UPLOADED → PARSING → READY（成功）或 FAILED（失败）
 */
export type DocumentStatus = 'UPLOADED' | 'PARSING' | 'READY' | 'FAILED'

/**
 * 文档响应对象（DocumentVO）。
 *
 * 对应后端文档实体。文件大小为 number 类型（字节），
 * 时间字段为 ISO-8601 UTC 字符串。
 */
export interface DocumentVO {
  /** 文档 ID */
  id: number
  /** 所属知识库 ID */
  knowledgeBaseId: number
  /** 文件名（含扩展名） */
  fileName: string
  /** 文件大小（字节数） */
  fileSize: number
  /** MIME 类型，如 application/pdf */
  contentType: string
  /** 文档状态 */
  status: DocumentStatus
  /** ISO-8601 UTC 字符串，上传时间 */
  createdAt: string
  /** ISO-8601 UTC 字符串，最后更新时间（含状态变更） */
  updatedAt: string
}

// ============================================================
// 知识库相关
// ============================================================

/**
 * 知识库访问模式。
 *
 * PRIVATE：仅知识库成员可见；
 * PUBLIC：企业内所有正常成员可见。
 */
export type KnowledgeBaseAccessMode = 'PRIVATE' | 'PUBLIC'

/**
 * 知识库状态。
 */
export type KnowledgeBaseStatus = 'NORMAL' | 'DISABLED'

/**
 * 知识库成员角色。
 *
 * 对应后端 KnowledgeBaseMemberRole 枚举。
 * - ADMIN：管理员，可管理知识库成员和设置
 * - EDITOR：编辑者，可编辑文档（后续步骤实现）
 * - VIEWER：只读，可查看知识库内容
 */
export type KnowledgeBaseMemberRole = 'VIEWER' | 'EDITOR' | 'ADMIN'

/**
 * 知识库响应对象（KnowledgeBaseVO）。
 *
 * 对应后端 io.github.qwertyhgb.knowflow.knowledgebase.vo.KnowledgeBaseVO。
 * 关键语义：
 * - myRole 表示当前登录用户在该知识库的成员角色，非成员为 null
 * - 创建知识库接口返回的 myRole 为 null（因为创建时成员关系尚未建立）
 * - ownerUserId 是创建者的用户 ID
 */
export interface KnowledgeBaseVO {
  id: number
  enterpriseId: number
  name: string
  /** 描述，可为 null */
  description: string | null
  accessMode: KnowledgeBaseAccessMode
  /** 创建者用户 ID */
  ownerUserId: number
  status: KnowledgeBaseStatus
  /** ISO-8601 UTC 字符串 */
  createdAt: string
  /** 当前用户的成员角色，非成员为 null */
  myRole: KnowledgeBaseMemberRole | null
}

/**
 * 知识库成员响应对象（KnowledgeBaseMemberVO）。
 *
 * 对应后端 io.github.qwertyhgb.knowflow.knowledgebase.vo.KnowledgeBaseMemberVO。
 * 注意：该 VO 不包含用户昵称/邮箱，前端需要通过企业成员列表接口
 * （EnterpriseMemberVO）把 userId 映射为昵称和邮箱展示。
 * 这是有意为之的 VO 最小化设计——避免跨域数据冗余。
 */
export interface KnowledgeBaseMemberVO {
  id: number
  knowledgeBaseId: number
  userId: number
  memberRole: KnowledgeBaseMemberRole
  /** ISO-8601 UTC 字符串 */
  createdAt: string
}

/** 创建知识库请求参数 */
export interface CreateKnowledgeBaseParams {
  /** 名称，必填，≤100 */
  name: string
  /** 描述，可空，≤500 */
  description?: string
  /** 访问模式，可空，默认 PRIVATE */
  accessMode?: KnowledgeBaseAccessMode
}

/**
 * 更新知识库请求参数。
 *
 * 注意：这是全量更新语义，必须传 accessMode（即使不改变也要传）。
 * 后端 PUT 接口要求全量字段，不像 PATCH 可以只传修改部分。
 */
export interface UpdateKnowledgeBaseParams {
  /** 名称，必填，≤100 */
  name: string
  /** 描述，≤500 */
  description?: string
  /** 访问模式，必填，全量语义 */
  accessMode: KnowledgeBaseAccessMode
}

/** 添加知识库成员请求参数 */
export interface AddKnowledgeBaseMemberParams {
  /** 用户 ID（必须是企业成员） */
  userId: number
  /** 授予的角色 */
  memberRole: KnowledgeBaseMemberRole
}

/** 修改知识库成员角色请求参数 */
export interface UpdateMemberRoleParams {
  memberRole: KnowledgeBaseMemberRole
}

// ============================================================
// 全文搜索相关
// ============================================================

/**
 * 文档搜索结果对象（DocumentSearchVO）。
 *
 * 对应后端全文搜索结果 VO。
 * 关键语义：
 * - contentSnippet 是 Elasticsearch 高亮片段，可能包含 <em> 标签包裹命中词
 *   （如 "<em>Redis</em> 缓存设计"），前端需用 v-html 渲染才能显示高亮样式；
 *   无高亮时为内容前 200 字符摘要。
 * - 后端已按当前企业 + 用户可见性过滤，搜索到的文档用户均可访问（无需再次鉴权）。
 */
export interface DocumentSearchVO {
  /** 文档 ID */
  documentId: number
  /** 所属知识库 ID */
  knowledgeBaseId: number
  /** 文件名（含扩展名） */
  fileName: string
  /** MIME 类型，如 application/pdf */
  contentType: string
  /** 高亮片段，可能含 <em> 标签；无高亮时为摘要；可能为 null */
  contentSnippet: string | null
  /** ISO-8601 UTC 字符串，文档创建（上传）时间 */
  createdAt: string
}

/**
 * 搜索结果分页对象（SearchResultVO）。
 *
 * items 为当前页结果；total 为总命中数（不是当前页条数）。
 * page 从 1 开始；size 默认 10，范围 1~100。
 */
export interface SearchResultVO {
  items: DocumentSearchVO[]
  /** 命中文档总数（用于分页显示"共命中 X 条"） */
  total: number
  /** 当前页码，从 1 开始 */
  page: number
  /** 每页条数 */
  size: number
}

/** 全文搜索请求参数（作为 URL query 参数发送） */
export interface SearchParams {
  /** 搜索关键词，必填 */
  keyword: string
  /** 页码，可选，默认 1，必须 ≥1 */
  page?: number
  /** 每页条数，可选，默认 10，范围 1~100 */
  size?: number
}