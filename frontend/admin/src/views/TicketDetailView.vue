<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Tickets } from '@element-plus/icons-vue'
import { useEnterpriseStore } from '../stores/enterprise'
import { useUserStore } from '../stores/user'
import { getMembers } from '../api/enterprise'
import {
  addTicketReply,
  assignTicket,
  getTicketDetail,
  updateTicketStatus,
  type TicketDetailVO,
  type TicketPriority,
  type TicketReplyVO,
  type TicketStatus,
} from '../api/ticket'
import { parseCitationsJson, type RagCitation } from '../api/aiChat'
import type { EnterpriseMemberVO } from '../api/types'

/**
 * 工单详情页。
 *
 * 展示「工单基本信息 + 多方对话流（用户/客服/AI）+ 按身份显示的操作区」。
 * 这是一个协作处理视图：不同身份看到和能做的操作不同——
 *   提交人：追加补充（USER）、确认关闭；
 *   被分配客服/管理员：回复（SUPPORT）、开始处理、标记解决、关闭；
 *   管理员（且工单 OPEN）：分配客服。
 *
 * 【工单对话流与会话消息的渲染差异（核心教学点）】
 * AI 助手会话只有两方（用户 / 助手），气泡左右二分即可；工单是多方的：
 * - USER（提交人）——右对齐蓝色，是「客户」视角；
 * - SUPPORT（客服）——左对齐白色，是「处理方」视角；
 * - AI（自动回答）——左对齐浅紫底 +「AI 助手」标记，是「系统」视角。
 * 三方气泡用 role 区分样式，目的是一眼看清「这句话是谁说的」，这也是后端
 * TicketReplyRole 要区分 USER/AI/SUPPORT 三值的原因——渲染层的区分必须以
 * 数据层的角色区分（而非前端猜）为前提。
 *
 * 【为什么前端按角色显隐只是体验层？】
 * 本页所有「按钮该不该显示」的判断（isManager / isAssignee / isSubmitter / status）
 * 只是让普通用户看不到无权按钮、交互更顺畅；真正的安全边界在后端——即使有人
 * 绕过前端直接调接口，后端也会按状态机 + 身份校验返回 403/409。
 */

const route = useRoute()
const router = useRouter()
const enterpriseStore = useEnterpriseStore()
const userStore = useUserStore()

/** 是否已选定企业 */
const hasEnterprise = computed(() => enterpriseStore.hasEnterprise)
/** 当前企业 ID（已选定时非空） */
const enterpriseId = computed(() => enterpriseStore.currentEnterpriseId!)
/** 工单 ID（来自路由参数） */
const ticketId = computed(() => Number(route.params.ticketId))
/** 当前登录用户 ID */
const currentUserId = computed(() => userStore.user?.id)

// ---------- 数据状态 ----------
/** 企业成员列表（用于解析处理人昵称 + 判断当前用户角色） */
const members = ref<EnterpriseMemberVO[]>([])
/** 工单详情（工单 + 回复） */
const detail = ref<TicketDetailVO | null>(null)
/** 加载中 */
const loading = ref(false)

/** 当前用户在该企业的角色编码（找不到成员记录时为 null） */
const currentUserRole = computed(() => {
  if (currentUserId.value === undefined) return null
  return members.value.find((m) => m.userId === currentUserId.value)?.roleCode ?? null
})

/** 是否企业管理员（OWNER/ADMIN）——可分配、可兜底处理 */
const isManager = computed(() => {
  const role = currentUserRole.value
  return role === 'OWNER' || role === 'ADMIN'
})

/** 工单（详情未加载时为 null） */
const ticket = computed(() => detail.value?.ticket ?? null)

/** 回复列表（工单详情中的全部回复，升序） */
const replies = computed(() => detail.value?.replies ?? [])

/** 当前用户是否是被分配的客服（处理人） */
const isAssignee = computed(() => ticket.value?.assigneeId === currentUserId.value)

/** 当前用户是否是提交人 */
const isSubmitter = computed(() => ticket.value?.userId === currentUserId.value)

/**
 * 是否「客服视角」：被分配处理人 或 企业 OWNER/ADMIN（管理兜底）。
 * 与后端 isSupportMember 的判定口径一致。
 */
const isSupport = computed(() => isAssignee.value || isManager.value)

// ---------- 回复输入 ----------
const replyInput = ref('')
const sending = ref(false)

// ---------- 分配对话框 ----------
const assignDialogVisible = ref(false)
const assigneeId = ref<number | null>(null)
const assigning = ref(false)

/** 可分配的成员（过滤掉已禁用的成员，只保留正常状态） */
const assignableMembers = computed(() =>
  members.value.filter((m) => m.status === 'NORMAL'),
)

onMounted(async () => {
  if (!hasEnterprise.value) return
  await Promise.all([loadMembers(), loadDetail()])
})

/** 拉取企业成员列表（用于角色判断 + 昵称映射） */
async function loadMembers() {
  try {
    members.value = await getMembers(enterpriseId.value)
  } catch {
    // 错误提示已由 http 拦截器统一处理；members 拉取失败时角色判断退化为「非管理员」
  }
}

/** 拉取工单详情 */
async function loadDetail() {
  loading.value = true
  try {
    detail.value = await getTicketDetail(enterpriseId.value, ticketId.value)
  } catch {
    // 错误提示已由 http 拦截器统一处理（如无权访问 404）
  } finally {
    loading.value = false
  }
}

// ==================== 回复 ====================

/**
 * 发送回复。
 *
 * 后端会根据当前身份自动判定回复角色（提交人→USER、被分配客服/管理员→SUPPORT），
 * 前端只传 content，不指定角色——防止伪造身份。发送成功后接口直接返回完整详情，
 * 用返回值替换本地 detail，免去一次重新拉取。
 */
async function handleSendReply() {
  const text = replyInput.value.trim()
  if (!text || sending.value) return
  sending.value = true
  try {
    detail.value = await addTicketReply(enterpriseId.value, ticketId.value, text)
    replyInput.value = ''
    ElMessage.success('回复成功')
  } catch {
    // 错误提示已由 http 拦截器统一处理
  } finally {
    sending.value = false
  }
}

/** Enter 发送、Shift+Enter 换行（与 AI 助手输入框交互一致） */
function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSendReply()
  }
}

// ==================== 分配 ====================

/** 打开分配对话框 */
function openAssignDialog() {
  assigneeId.value = null
  assignDialogVisible.value = true
}

/** 提交分配：调后端把工单分配给选中的成员 */
async function handleAssign() {
  if (assigneeId.value === null) return
  assigning.value = true
  try {
    // 后端校验：仅 OWNER/ADMIN、工单须 OPEN、目标须为企业正常成员；
    // 成功后返回更新后的工单，直接替换本地 ticket 即可（回复列表不变）。
    const updated = await assignTicket(enterpriseId.value, ticketId.value, assigneeId.value)
    if (detail.value) detail.value.ticket = updated
    ElMessage.success('已分配客服')
    assignDialogVisible.value = false
  } catch {
    // 错误提示已由 http 拦截器统一处理
  } finally {
    assigning.value = false
  }
}

// ==================== 状态流转 ====================

/**
 * 执行一次状态流转（开始处理 / 标记解决 / 关闭 / 确认关闭）。
 *
 * 【为什么「关闭」要拆成两条路径按身份显示？】
 * 后端状态机允许两条到 CLOSED 的路径：
 * - 客服（PROCESSING → CLOSED）：无效/重复工单等无需用户确认的场景；
 * - 提交人（RESOLVED → CLOSED）确认关闭：解决是客服的判断、关闭是用户的认可。
 * 前端按「身份 + 当前状态」分别显示对应按钮，避免提交人看到「标记解决」、
 * 也避免在 RESOLVED 状态还让客服重复看到「解决」按钮——按钮显隐与后端合法
 * 操作对齐，减少无效点击；真正的拒绝（403/409）仍由后端兜底。
 */
async function changeStatus(
  status: TicketStatus,
  confirmText: string,
  tip?: string,
): Promise<void> {
  try {
    await ElMessageBox.confirm(confirmText, '确认操作', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }

  try {
    const updated = await updateTicketStatus(enterpriseId.value, ticketId.value, status)
    if (detail.value) detail.value.ticket = updated
    ElMessage.success(tip ?? '操作成功')
  } catch {
    // 错误提示已由 http 拦截器统一处理（如非法流转 409、无权 403）
  }
}

// ==================== 展示辅助 ====================

/** 用户 ID → 显示名（昵称优先，缺失回退邮箱，再回退「用户{id}」） */
function memberName(userId: number | null): string {
  if (userId === null) return ''
  const m = members.value.find((x) => x.userId === userId)
  return m?.nickname || m?.email || `用户${userId}`
}

/** 处理人显示名：null → 「未分配」 */
function assigneeName(assigneeId: number | null): string {
  return assigneeId === null ? '未分配' : memberName(assigneeId) || '未分配'
}

/** 回复来源标签（气泡上方的小字标识「谁说的」） */
function senderLabel(reply: TicketReplyVO): string {
  if (reply.role === 'AI') return 'AI 助手'
  if (reply.role === 'SUPPORT') return memberName(reply.senderId) || '客服'
  // USER：提交人；若是自己则显示「我」更符合直觉
  return reply.senderId === currentUserId.value ? '我' : memberName(reply.senderId) || '提交人'
}

/**
 * 回复对齐与气泡样式。
 * USER 右对齐蓝色（客户）、SUPPORT 左对齐白色（客服）、AI 左对齐浅紫（系统）。
 */
function replyAlign(role: TicketReplyVO['role']): 'left' | 'right' {
  return role === 'USER' ? 'right' : 'left'
}
function bubbleClass(role: TicketReplyVO['role']): string {
  if (role === 'USER') return 'bubble-user'
  if (role === 'SUPPORT') return 'bubble-support'
  return 'bubble-ai'
}

/** 解析某条回复的引用数组（AI 回复可能有；其他角色返回空） */
function citationsOf(reply: TicketReplyVO): RagCitation[] {
  return reply.role === 'AI' ? parseCitationsJson(reply.citationsJson) : []
}

/** 点击引用卡片：跳转到对应知识库详情页 */
function openKnowledgeBase(citation: RagCitation) {
  router.push(`/knowledge-bases/${citation.knowledgeBaseId}`)
}

/** 相关度百分比显示（score 为 null 时显示占位符） */
function formatScore(score: number | null): string {
  if (score === null) return '-'
  return `相关度 ${Math.round(score * 100)}%`
}

// ---------- 分类/优先级/状态映射 ----------

const categoryLabelMap: Record<string, string> = {
  CONSULTATION: '咨询',
  ISSUE: '问题反馈',
  FEEDBACK: '建议',
  OTHER: '其他',
}
function categoryLabel(c: string): string {
  return categoryLabelMap[c] ?? c
}

const priorityLabelMap: Record<TicketPriority, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  URGENT: '紧急',
}
function priorityLabel(p: TicketPriority): string {
  return priorityLabelMap[p] ?? p
}
function priorityTag(p: TicketPriority): 'info' | 'primary' | 'warning' | 'danger' {
  const map: Record<TicketPriority, 'info' | 'primary' | 'warning' | 'danger'> = {
    LOW: 'info',
    MEDIUM: 'primary',
    HIGH: 'warning',
    URGENT: 'danger',
  }
  return map[p] ?? 'info'
}

const statusLabelMap: Record<string, string> = {
  OPEN: '待处理',
  ASSIGNED: '已分配',
  PROCESSING: '处理中',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
}
function statusLabel(s: string): string {
  return statusLabelMap[s] ?? s
}
function statusTag(s: string): 'info' | 'primary' | 'warning' | 'success' {
  switch (s) {
    case 'ASSIGNED':
      return 'primary'
    case 'PROCESSING':
      return 'warning'
    case 'RESOLVED':
      return 'success'
    default:
      return 'info'
  }
}

/** 格式化时间：ISO UTC 字符串转本地时间 */
function formatTime(iso: string | undefined): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}
</script>

<template>
  <div class="ticket-detail">
    <!-- ============ 未选定企业：引导提示 ============ -->
    <div v-if="!hasEnterprise" class="guide-card">
      <el-icon :size="56" color="#c0c4cc"><Tickets /></el-icon>
      <h3 class="guide-title">请先选择企业</h3>
      <p class="guide-desc">工单从属于企业，请先在企业管理中选择要操作的企业。</p>
      <el-button type="primary" @click="router.push('/enterprise')">前往企业管理</el-button>
    </div>

    <template v-else>
      <!-- ============ 顶部返回栏 + 标题 + 操作区 ============ -->
      <div class="detail-head">
        <div class="head-left">
          <el-button link :icon="ArrowLeft" @click="router.push('/tickets')">
            返回工单列表
          </el-button>
        </div>

        <!-- 操作区：按「身份 + 状态」显示对应按钮（见 changeStatus 注释） -->
        <div v-if="ticket" class="head-actions">
          <!-- 管理员 + OPEN：分配客服 -->
          <el-button
            v-if="isManager && ticket.status === 'OPEN'"
            type="primary"
            @click="openAssignDialog"
          >
            分配客服
          </el-button>

          <!-- 客服 + ASSIGNED：开始处理 -->
          <el-button
            v-if="isSupport && ticket.status === 'ASSIGNED'"
            type="primary"
            @click="changeStatus('PROCESSING', '确认开始处理该工单吗？', '已开始处理')"
          >
            开始处理
          </el-button>

          <!-- 客服 + PROCESSING：标记解决 / 关闭工单 -->
          <template v-if="isSupport && ticket.status === 'PROCESSING'">
            <el-button
              type="success"
              @click="changeStatus('RESOLVED', '确认标记该工单为已解决吗？', '已标记解决')"
            >
              标记解决
            </el-button>
            <el-button
              type="warning"
              @click="changeStatus('CLOSED', '确认关闭该工单吗？关闭后不可继续操作。', '工单已关闭')"
            >
              关闭工单
            </el-button>
          </template>

          <!-- 客服 + RESOLVED：关闭工单（解决后仍需关闭收尾） -->
          <el-button
            v-if="isSupport && ticket.status === 'RESOLVED'"
            type="warning"
            @click="changeStatus('CLOSED', '确认关闭该工单吗？', '工单已关闭')"
          >
            关闭工单
          </el-button>

          <!-- 提交人 + RESOLVED：确认关闭（用户认可已解决） -->
          <el-button
            v-if="isSubmitter && !isSupport && ticket.status === 'RESOLVED'"
            type="success"
            @click="changeStatus('CLOSED', '问题已解决，确认关闭该工单吗？', '工单已关闭')"
          >
            确认关闭
          </el-button>

          <!-- CLOSED：终态，无操作，仅显示状态标签 -->
          <el-tag v-if="ticket.status === 'CLOSED'" type="info" size="large">
            已关闭
          </el-tag>
        </div>
      </div>

      <div v-loading="loading">
        <template v-if="ticket">
          <!-- ============ 工单标题 + 状态/优先级/分类 ============ -->
          <div class="ticket-header">
            <h2 class="ticket-title">{{ ticket.title }}</h2>
            <div class="ticket-tags">
              <el-tag :type="statusTag(ticket.status)" effect="light">
                {{ statusLabel(ticket.status) }}
              </el-tag>
              <el-tag :type="priorityTag(ticket.priority)" effect="light">
                优先级：{{ priorityLabel(ticket.priority) }}
              </el-tag>
              <el-tag type="info" effect="light">分类：{{ categoryLabel(ticket.category) }}</el-tag>
            </div>
          </div>

          <!-- ============ 基本信息卡片 ============ -->
          <div class="info-card">
            <div class="info-desc">{{ ticket.description }}</div>
            <div class="info-meta">
              <span>提交时间：{{ formatTime(ticket.createdAt) }}</span>
              <span>处理人：{{ assigneeName(ticket.assigneeId) }}</span>
            </div>
          </div>

          <!-- ============ 对话流 ============ -->
          <div class="reply-card">
            <h3 class="reply-title">对话记录</h3>
            <div class="reply-list">
              <div
                v-for="reply in replies"
                :key="reply.id"
                class="reply-row"
                :class="replyAlign(reply.role)"
              >
                <div class="reply-block">
                  <span class="reply-sender">{{ senderLabel(reply) }}</span>
                  <div class="reply-bubble" :class="bubbleClass(reply.role)">
                    {{ reply.content }}
                  </div>

                  <!-- AI 的引用来源（仅 AI 回复且存在引用时显示） -->
                  <div
                    v-if="citationsOf(reply).length > 0"
                    class="citations"
                  >
                    <div class="citations-title">引用来源</div>
                    <div
                      v-for="(citation, cIndex) in citationsOf(reply)"
                      :key="cIndex"
                      class="citation-card"
                      @click="openKnowledgeBase(citation)"
                    >
                      <div class="citation-main">
                        <span class="citation-file">{{ citation.fileName }}</span>
                        <span class="citation-chunk">第 {{ citation.chunkIndex + 1 }} 块</span>
                      </div>
                      <div class="citation-score">{{ formatScore(citation.score) }}</div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 回复输入区（底部固定） -->
            <div class="reply-input-area">
              <textarea
                v-model="replyInput"
                class="reply-input"
                placeholder="输入回复内容，Enter 发送，Shift+Enter 换行"
                @keydown="handleKeydown"
              ></textarea>
              <el-button
                type="primary"
                class="reply-send-btn"
                :disabled="!replyInput.trim() || sending"
                :loading="sending"
                @click="handleSendReply"
              >
                发送
              </el-button>
            </div>
          </div>
        </template>
      </div>
    </template>

    <!-- ============ 分配客服对话框 ============ -->
    <el-dialog v-model="assignDialogVisible" title="分配客服" width="480px">
      <el-form label-position="top">
        <el-form-item label="选择客服">
          <el-select
            v-model="assigneeId"
            placeholder="请选择要分配的客服"
            style="width: 100%"
          >
            <el-option
              v-for="m in assignableMembers"
              :key="m.userId"
              :label="`${m.nickname || m.email || '未命名'}（${m.email || '无邮箱'}）`"
              :value="m.userId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="assigning"
          :disabled="assigneeId === null"
          @click="handleAssign"
        >
          确定分配
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ticket-detail {
  max-width: 960px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ---------- 引导卡片 ---------- */
.guide-card {
  text-align: center;
  background: #ffffff;
  border-radius: 16px;
  padding: 60px 40px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
}

.guide-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin: 16px 0 8px;
}

.guide-desc {
  font-size: 14px;
  color: #909399;
  margin-bottom: 24px;
}

/* ---------- 顶部栏 ---------- */
.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.head-left {
  display: flex;
  align-items: center;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

/* ---------- 工单标题 ---------- */
.ticket-header {
  background: #ffffff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ticket-title {
  font-size: 20px;
  font-weight: 600;
  color: #1f2d3d;
  margin: 0;
  word-break: break-word;
}

.ticket-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

/* ---------- 基本信息卡片 ---------- */
.info-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
}

.info-desc {
  font-size: 14px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
  margin-bottom: 16px;
}

.info-meta {
  display: flex;
  gap: 32px;
  font-size: 13px;
  color: #909399;
  padding-top: 12px;
  border-top: 1px solid #f0f2f5;
}

/* ---------- 对话流 ---------- */
.reply-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
}

.reply-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2d3d;
  margin: 0 0 16px;
}

.reply-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-height: 420px;
  overflow-y: auto;
  padding: 4px;
}

.reply-row {
  display: flex;
}

.reply-row.left {
  justify-content: flex-start;
}

.reply-row.right {
  justify-content: flex-end;
}

.reply-block {
  display: flex;
  flex-direction: column;
  max-width: 78%;
  gap: 4px;
}

.reply-row.right .reply-block {
  align-items: flex-end;
}

.reply-sender {
  font-size: 12px;
  color: #909399;
  padding: 0 4px;
}

.reply-bubble {
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 提交人（客户）：右对齐蓝色 */
.bubble-user {
  background: linear-gradient(135deg, #409eff, #66b1ff);
  color: #fff;
  border-radius: 16px 16px 4px 16px;
}

/* 客服：左对齐白色 */
.bubble-support {
  background: #ffffff;
  color: #303133;
  border: 1px solid #ebeef5;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  border-radius: 16px 16px 16px 4px;
}

/* AI 助手：左对齐浅紫底 */
.bubble-ai {
  background: #f4f0ff;
  color: #303133;
  border: 1px solid #e6dcff;
  border-radius: 16px 16px 16px 4px;
}

/* ---------- 引用卡片（与 AI 助手页复用样式） ---------- */
.citations {
  width: 100%;
  margin-top: 4px;
}

.citations-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
  padding-left: 2px;
}

.citation-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  padding: 8px 12px;
  margin-bottom: 6px;
  cursor: pointer;
  transition: border-color 0.2s ease, background-color 0.2s ease, transform 0.2s ease;
}

.citation-card:hover {
  background: #ecf5ff;
  border-color: #a0cfff;
  transform: translateY(-1px);
}

.citation-main {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.citation-file {
  font-size: 13px;
  font-weight: 600;
  color: #409eff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.citation-chunk {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}

.citation-score {
  font-size: 12px;
  color: #67c23a;
  flex-shrink: 0;
  font-weight: 500;
}

/* ---------- 回复输入区 ---------- */
.reply-input-area {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding-top: 16px;
  margin-top: 16px;
  border-top: 1px solid #ebeef5;
}

.reply-input {
  flex: 1;
  border: 1px solid #dcdfe6;
  border-radius: 12px;
  padding: 12px 14px;
  font-size: 14px;
  line-height: 1.6;
  color: #303133;
  resize: none;
  outline: none;
  min-height: 44px;
  max-height: 160px;
  font-family: inherit;
  transition: border-color 0.2s ease;
}

.reply-input:focus {
  border-color: #409eff;
}

.reply-input::placeholder {
  color: #c0c4cc;
}

.reply-send-btn {
  flex-shrink: 0;
  height: 44px;
  min-width: 76px;
  border-radius: 12px;
  font-weight: 500;
}
</style>