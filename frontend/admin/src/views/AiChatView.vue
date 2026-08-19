<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus, Service } from '@element-plus/icons-vue'
import {
  conversationRagChatStream,
  createConversation,
  deleteConversation,
  getConversationDetail,
  listConversations,
  parseCitationsJson,
  type ConversationVO,
  type RagCitation,
} from '../api/aiChat'

/**
 * AI 助手对话页（多会话 RAG）。
 *
 * 多会话形态：左侧会话栏（新建/切换/删除）+ 右侧聊天窗口。每个会话独立持久化在
 * 后端（Phase 12），消息与引用落库，刷新/切换会话后历史完整回看。
 *
 * 【为什么消息/会话不存本地、每次从后端拉？】后端是唯一数据源，存数据库（用户级资源）。
 * 前端只负责「进入页面拉列表 → 选会话拉详情 → 发送流式回答」，不保留任何本地副本，
 * 这样任何设备/任何时间打开看到的状态都一致（会话服务化，而非单页记忆）。
 */

/** RAG 检索参数：Phase 11 后端验证过的合理默认值（发送时传给后端）。 */
const TOP_K = 5
const SCORE_THRESHOLD = 0.3

/** 单条聊天消息（前端视图结构；历史消息由后端行映射而来） */
interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  /** 是否为错误提示（灰色显示） */
  error?: boolean
  /** 引用来源（只属于 assistant 消息；user 消息没有）。 */
  citations?: RagCitation[]
}

// ==================== 状态 ====================

/** 会话列表（按 updated_at 倒序，新建的插到头部） */
const conversations = ref<ConversationVO[]>([])
/** 当前选中的会话 ID；null 表示无会话（未选或都删光了） */
const currentConversationId = ref<number | null>(null)
/** 切换会话拉取历史时的 loading（右侧聊天区 v-loading 遮罩） */
const conversationLoading = ref(false)
/** 消息列表：当前会话的气泡 */
const messages = ref<ChatMessage[]>([])

/** 输入框内容 */
const input = ref('')
/** 是否正在接收流式回答（控制发送按钮 → 停止按钮切换） */
const streaming = ref(false)
/** 当前流式请求的 AbortController，用于「停止生成」时中断 */
let activeController: AbortController | null = null

/** 消息列表容器 DOM，用于自动滚动到底部 */
const listRef = ref<HTMLDivElement>()
/** 输入框 DOM，新建会话后聚焦 */
const inputRef = ref<HTMLTextAreaElement>()

/** 路由实例：引用卡片点击跳转知识库详情用 */
const router = useRouter()

/** 当前会话标题：显示在聊天区头部（新会话未发送前为后端默认「新对话」） */
const currentTitle = computed(
  () =>
    conversations.value.find((c) => c.id === currentConversationId.value)?.title ??
    '',
)

// ==================== 页面加载 ====================

onMounted(async () => {
  try {
    conversations.value = await listConversations()
  } catch {
    // 列表拉取失败（如会话服务异常）：显示空态，错误提示已由 http 拦截器统一处理
  }
  // 有会话则自动选中第一个（列表已按 updated_at 倒序，第一个是最近活跃的）
  if (conversations.value.length > 0) {
    await setCurrentConversation(conversations.value[0].id)
  }
})

// ==================== 会话管理 ====================

/**
 * 新建会话。
 *
 * 调后端创建（标题为默认「新对话」）→ 插入列表头部 → 选中 → 清空消息 → 聚焦输入框。
 * 【为什么不本地拼一个假会话？】会话 ID 必须由后端生成（删除/加载历史都依赖它），
 * 必须走真实创建接口。
 */
async function startNewConversation() {
  try {
    const conv = await createConversation()
    conversations.value.unshift(conv)
    currentConversationId.value = conv.id
    messages.value = []
    // 聚焦输入框：让用户新建后可以直接开打
    nextTick(() => inputRef.value?.focus())
  } catch {
    // 创建失败提示已由 http 拦截器统一处理
  }
}

/** 切换会话并加载其历史消息。 */
async function setCurrentConversation(id: number) {
  // 若有进行中的流式回答，先停止，避免回答写到错误会话里
  stop()

  currentConversationId.value = id
  conversationLoading.value = true
  try {
    const detail = await getConversationDetail(id)
    // 后端返回的每行消息映射为前端 ChatMessage 结构（见 toChatMessage 注释）
    messages.value = detail.messages.map(toChatMessage)
    scrollToBottom()
  } catch {
    // 详情拉取失败：错误提示已由 http 拦截器处理；清空消息避免展示旧会话内容
    messages.value = []
  } finally {
    conversationLoading.value = false
  }
}

/**
 * 把后端消息行映射为前端视图结构。
 *
 * 【为什么历史消息要用这个函数转一次格式？】后端返回的就是最简结构
 * （role 为后端枚举名 USER/ASSISTANT，引用是落库 JSON 字符串），不是前端渲染结构；
 * 且有「历史消息无错误态」约定。所以前端必须映射一次：role 大小写转换、
 * citationsJson 防御式解析成数组、error 置 false。这是「后端最简数据 → 前端视图数据」
 * 的标准转换层，放在组件内即可（只有会话详情一处用）。
 */
function toChatMessage(row: {
  role: 'USER' | 'ASSISTANT'
  content: string
  citationsJson: string | null
}): ChatMessage {
  return {
    role: row.role === 'USER' ? 'user' : 'assistant',
    content: row.content,
    // 只有 assistant 消息可能有引用；user 消息解析为空，渲染时自然不显示引用区
    citations: parseCitationsJson(row.citationsJson),
    // 历史消息永远不是错误态（错误态是流式过程中临时标记，不落库）
    error: false,
  }
}

/**
 * 删除会话。
 *
 * 【为什么删除必须二次确认？】删除会连同全部历史消息一起级联删除，不可恢复——
 * 是破坏性操作，与项目其他删除（如知识库）一致，误触代价高，故用 ElMessageBox 确认。
 */
async function handleDelete(conv: ConversationVO, event: Event) {
  // 阻止事件冒泡：删除按钮在会话项内部，点击不该同时触发「选中该会话」
  event.stopPropagation()

  // 用户取消 → 直接结束，deleteConversation 不会被调用
  try {
    await ElMessageBox.confirm(
      '删除后该会话及全部消息将不可恢复，确认删除？',
      '删除会话',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  try {
    await deleteConversation(conv.id)
  } catch {
    return // 删除失败提示已由 http 拦截器统一处理
  }

  conversations.value = conversations.value.filter((c) => c.id !== conv.id)
  ElMessage.success('会话已删除')

  // 删除的正是当前会话：回到空态（无选中会话 + 清空消息）
  if (currentConversationId.value === conv.id) {
    currentConversationId.value = null
    messages.value = []
  }
  // 删除非当前会话：不影响当前聊天，无需额外处理
}

// ==================== 发送 / 流式 ====================

/** 复位接收态：onDone / onError / stop 都会走到这里 */
function reset() {
  streaming.value = false
  activeController = null
}

/** 停止生成：中断当前流式请求 */
function stop() {
  // abort 后 fetch 抛 AbortError，conversationRagChatStream 内部静默处理不触发回调，
  // 所以这里必须手动复位状态（否则按钮卡在「停止」）
  activeController?.abort()
  reset()
}

/** 滚动到底部：新消息或流式追加内容时调用 */
function scrollToBottom() {
  // nextTick 等本次 DOM 更新后再读 scrollHeight，否则拿的是旧高度，滚不到底
  nextTick(() => {
    const el = listRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

/**
 * 发送消息（会话内 RAG 流式）。
 *
 * 流程：确保会话存在（无则自动创建）→ 追加用户消息 → 追加空的 assistant 占位 →
 * 调 conversationRagChatStream，onCitations 挂引用、onChunk 追加分片、
 * onError 灰显、onDone 复位并刷新会话列表。
 */
async function send() {
  const text = input.value.trim()
  if (!text) return
  if (streaming.value) return

  // ---- 确保会话存在：无则先自动创建 ----
  // 【为什么发送时自动建会话？】主流聊天产品的行为：用户直接输入即开新会话，
  // 不必先点「新建」。这样少一步操作，体验平滑；若创建失败则本次发送作罢。
  let convId = currentConversationId.value
  if (convId === null) {
    try {
      const conv = await createConversation()
      conversations.value.unshift(conv)
      currentConversationId.value = conv.id
      convId = conv.id
    } catch {
      return // 创建失败提示已由 http 拦截器统一处理
    }
  }

  // 1. 追加用户消息
  messages.value.push({ role: 'user', content: text })
  // 2. 清空输入框
  input.value = ''
  // 3. 追加空的 assistant 占位消息，流式期间它就是数组最后一条，用索引更新
  messages.value.push({ role: 'assistant', content: '' })
  const assistantIndex = messages.value.length - 1

  streaming.value = true
  scrollToBottom()

  // 复用会话内 RAG 流式接口：后端先发 citations 引用事件，再逐块推回答分片
  activeController = conversationRagChatStream(
    convId,
    text,
    TOP_K,
    SCORE_THRESHOLD,
    {
      // 引用先于回答到达：拿到立即挂到占位消息上，回答流式显示时 [1] 已可对应卡片
      onCitations: (citations) => {
        messages.value[assistantIndex].citations = citations
        scrollToBottom()
      },
      // 每到一个分片，追加到对应 assistant 消息（用索引定位，见上方注释）
      onChunk: (chunk) => {
        messages.value[assistantIndex].content += chunk
        scrollToBottom()
      },
      // 出错：占位消息替换为错误提示并灰显
      onError: (message) => {
        messages.value[assistantIndex].content = message
        messages.value[assistantIndex].error = true
        reset()
      },
      // 流正常结束：复位接收态 + 刷新会话列表
      onDone: () => {
        reset()
        refreshList()
      },
    },
  )
}

/**
 * 发送结束后重新拉取会话列表。
 *
 * 【为什么要重新拉列表而不是本地更新该会话项？】标题由后端自动生成
 * （首条消息前 20 字），前端不知道服务端最终生成的标题；即便能猜，updated_at 等
 * 字段也由后端维护。简单做法：发送结束重新拉一次列表，让标题/时间与服务端一致，
 * 代价是少量网络请求，换取「列表永远可信」的简单性。
 */
async function refreshList() {
  try {
    conversations.value = await listConversations()
  } catch {
    // 失败不影响当前聊天，仅列表未及时刷新，可下次再刷
  }
}

// ==================== 交互辅助 ====================

/**
 * 会话时间显示：今天显示 HH:mm，今年显示 MM-DD，更早显示 YYYY-MM-DD。
 *
 * 【为什么不用相对时间（「5 分钟前」）？】相对时间需要定时器每秒/分钟刷新，
 * 否则会「停在 5 分钟前」失真。绝对时间静态渲染即可，教学阶段更简单可靠。
 */
function formatTime(iso: string): string {
  const d = new Date(iso)
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  // 今天：HH:mm
  if (d.toDateString() === now.toDateString()) {
    return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  }
  // 今年：MM-DD
  if (d.getFullYear() === now.getFullYear()) {
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  }
  // 更早：YYYY-MM-DD
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 点击引用卡片：跳转到该知识库详情页。 */
function openKnowledgeBase(citation: RagCitation) {
  router.push(`/knowledge-bases/${citation.knowledgeBaseId}`)
}

/** 把相似度分数格式化为「相关度 82%」（score 为 null 时显示占位符）。 */
function formatScore(score: number | null): string {
  if (score === null) return '-'
  return `相关度 ${Math.round(score * 100)}%`
}

/**
 * 输入框按键：Enter 发送、Shift+Enter 换行。
 *
 * 【为什么用 keydown + preventDefault？】见原实现注释：多行输入框按 Enter 是换行，
 * 需在 keydown 阶段 preventDefault 才能「阻止换行 + 触发发送」二合一；
 * Shift+Enter 是明确的换行意图，用 event.shiftKey 区分，符合主流聊天产品习惯。
 */
function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
  // Shift+Enter 不做处理，让浏览器自然换行
}
</script>

<template>
  <div class="chat-page">
    <!-- ==================== 左侧会话栏 ==================== -->
    <aside class="session-panel">
      <!-- 新建会话按钮：占满宽度 -->
      <div class="session-header">
        <el-button
          type="primary"
          class="new-btn"
          :icon="Plus"
          @click="startNewConversation"
        >
          新建会话
        </el-button>
      </div>

      <!-- 会话列表 -->
      <div class="session-list">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="session-item"
          :class="{ active: conv.id === currentConversationId }"
          @click="setCurrentConversation(conv.id)"
        >
          <span class="session-title" :title="conv.title">{{ conv.title }}</span>
          <span class="session-time">{{ formatTime(conv.updatedAt) }}</span>
          <!-- 删除图标：hover 会话项时才显示（避免常驻视觉噪音） -->
          <el-icon
            class="session-delete"
            :size="15"
            @click="handleDelete(conv, $event)"
          >
            <Delete />
          </el-icon>
        </div>
      </div>
    </aside>

    <!-- ==================== 右侧聊天区 ==================== -->
    <div class="chat-card">
      <!-- 聊天区头部：显示当前会话标题 -->
      <div class="chat-header">
        <span class="chat-title">{{ currentTitle || 'AI 助手' }}</span>
      </div>

      <!-- 消息列表（可滚动） -->
      <div v-loading="conversationLoading" ref="listRef" class="chat-list">
        <!-- 空态：无消息时居中引导（无会话或无消息都显示） -->
        <div v-if="messages.length === 0" class="empty-state">
          <div class="empty-avatar">
            <el-icon :size="40"><Service /></el-icon>
          </div>
          <p class="empty-title">开始你的第一段对话</p>
          <p class="empty-desc">问我对企业知识库的任何问题，我会流式作答</p>
        </div>

        <!-- 消息气泡列表 -->
        <div
          v-for="(msg, index) in messages"
          :key="index"
          class="message-row"
          :class="msg.role"
        >
          <div class="assistant-block">
            <div class="bubble" :class="[msg.role, { error: msg.error }]">
              {{ msg.content }}
              <!-- 流式接收中的尾部闪烁光标：只显示在正在接收的最后那条 assistant 消息 -->
              <span
                v-if="streaming && msg.role === 'assistant' && index === messages.length - 1"
                class="cursor"
              ></span>
            </div>

            <!-- 引用来源区：属于 assistant 且存在引用才显示 -->
            <div
              v-if="msg.role === 'assistant' && msg.citations && msg.citations.length > 0"
              class="citations"
            >
              <div class="citations-title">引用来源</div>
              <div
                v-for="(citation, cIndex) in msg.citations"
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

      <!-- 输入区（固定在底部） -->
      <div class="input-area">
        <textarea
          ref="inputRef"
          v-model="input"
          class="chat-input"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          @keydown="handleKeydown"
        ></textarea>
        <el-button
          v-if="!streaming"
          type="primary"
          class="send-btn"
          :disabled="!input.trim()"
          @click="send"
        >
          发送
        </el-button>
        <el-button v-else type="danger" class="send-btn" @click="stop">
          停止
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  height: 100%;
  display: flex;
  gap: 20px;
}

/* ---------------- 左侧会话栏 ---------------- */
.session-panel {
  width: 260px;
  flex-shrink: 0;
  height: 100%;
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.session-header {
  padding: 16px;
  border-bottom: 1px solid #f0f2f5;
}

.new-btn {
  width: 100%;
  border-radius: 10px;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

/* 会话项：标题(省略) + 时间(小字)；hover 显示删除图标 */
.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  margin-bottom: 2px;
  transition: background-color 0.2s ease;
}

.session-item:hover {
  background: #f5f7fa;
}

/* 选中态：浅蓝渐变底 + 蓝字，圆角高亮（与全站菜单选中态一致） */
.session-item.active {
  background: linear-gradient(135deg, #ecf5ff, #d9ecff);
  color: #409eff;
}

.session-title {
  flex: 1;
  font-size: 14px;
  /* 单行省略：标题可能很长，不换行撑高 */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-time {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}

/* 删除图标：默认隐藏，hover 会话项时淡入 */
.session-delete {
  flex-shrink: 0;
  color: #909399;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.2s ease, color 0.2s ease;
}

.session-delete:hover {
  color: #f56c6c;
}

.session-item:hover .session-delete {
  opacity: 1;
}

/* ---------------- 右侧聊天区 ---------------- */
.chat-card {
  flex: 1;
  height: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
  overflow: hidden;
}

.chat-header {
  flex-shrink: 0;
  height: 52px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  border-bottom: 1px solid #f0f2f5;
}

.chat-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  /* 标题长省略号 */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  background: #fafbfc;
}

.empty-state {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #909399;
}

.empty-avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: linear-gradient(135deg, #409eff, #66b1ff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
  box-shadow: 0 8px 20px rgba(64, 158, 255, 0.3);
}

.empty-title {
  font-size: 17px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 6px;
}

.empty-desc {
  font-size: 13px;
  margin: 0;
}

.message-row {
  display: flex;
  margin-bottom: 16px;
}

.message-row.user {
  justify-content: flex-end;
}

.message-row.assistant {
  justify-content: flex-start;
}

.assistant-block {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  max-width: 78%;
}

.bubble {
  max-width: 78%;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble.user {
  background: linear-gradient(135deg, #409eff, #66b1ff);
  color: #fff;
  border-radius: 16px 16px 4px 16px;
}

.bubble.assistant {
  background: #ffffff;
  color: #303133;
  border: 1px solid #ebeef5;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  border-radius: 16px 16px 16px 4px;
}

.bubble.error {
  color: #909399;
  background: #f5f7fa;
  border-color: #e4e7ed;
  box-shadow: none;
}

.citations {
  width: 100%;
  margin-top: 8px;
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

.cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  background: #409eff;
  vertical-align: text-bottom;
  animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}

.input-area {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid #ebeef5;
  background: #ffffff;
}

.chat-input {
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

.chat-input:focus {
  border-color: #409eff;
}

.chat-input::placeholder {
  color: #c0c4cc;
}

.send-btn {
  flex-shrink: 0;
  height: 44px;
  min-width: 76px;
  border-radius: 12px;
  font-weight: 500;
}
</style>