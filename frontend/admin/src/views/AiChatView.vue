<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { Service } from '@element-plus/icons-vue'
import { chatStream } from '../api/aiChat'

/**
 * AI 助手对话页。
 *
 * 一个「无状态」的单轮对话界面：用户提问，后端逐字流式返回回答，前端以
 * 打字机效果展示。界面参考主流 AI 对话产品（气泡 + 输入区 + 流式光标）。
 *
 * 【为什么消息只存在组件内存里，刷新即清空？】
 * 后端当前的流式接口是「无状态」调用：每次请求独立，后端不保存任何会话记忆，
 * 前端展示的只是「本次会话」的气泡。多轮记忆与真正的会话管理是后端 Phase 12
 * 的主题，届时再把消息持久化并接入会话历史。所以这里刻意不做持久化——
 * 刷新清空是符合当前后端能力的预期行为。
 */

/** 单条聊天消息 */
interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  /** 是否为错误提示（灰色显示） */
  error?: boolean
}

/** 消息列表：只存本次会话的气泡，不持久化（见上方注释） */
const messages = ref<ChatMessage[]>([])

/** 输入框内容 */
const input = ref('')

/** 是否正在接收流式回答（控制发送按钮 → 停止按钮的切换） */
const streaming = ref(false)

/** 当前流式请求的 AbortController，用于「停止生成」时中断请求 */
let activeController: AbortController | null = null

/** 消息列表容器 DOM，用于自动滚动到底部 */
const listRef = ref<HTMLDivElement>()

/** 复位接收态：onDone / onError / stop 都会走到这里，保证状态不残留 */
function reset() {
  streaming.value = false
  activeController = null
}

/** 滚动到底部：新消息或流式追加内容时调用 */
function scrollToBottom() {
  // nextTick 等待本次 DOM 更新完成后再读 scrollHeight，否则拿到的是旧高度，
  // 会差「一条新消息的高度」，导致滚不到最底。
  nextTick(() => {
    const el = listRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

/**
 * 发送消息。
 *
 * 流程：push 用户消息 → push 空的 assistant 占位消息 → 调 chatStream，
 * 在 onChunk 里把分片追加到占位消息、onError 里替换为错误提示、onDone 复位状态。
 */
function send() {
  const text = input.value.trim()
  // 空消息（或纯空格）不发请求
  if (!text) return
  // 流式接收中不允许并发发送：此时按钮是「停止」，用户必须先停止才能发下一条
  if (streaming.value) return

  // 1. 追加用户消息
  messages.value.push({ role: 'user', content: text })
  // 2. 清空输入框
  input.value = ''
  // 3. 追加一条空的 assistant 占位消息，用于承载流式分片
  messages.value.push({ role: 'assistant', content: '' })
  // 记录占位消息的索引：流式期间它就是数组最后一条，回调里用索引更新它
  // （而不是在回调里 push 新消息——那样会每到一个分片就多一条气泡，完全错误）
  const assistantIndex = messages.value.length - 1

  streaming.value = true
  scrollToBottom()

  activeController = chatStream(text, {
    // 每到一个分片，追加到对应 assistant 消息的 content（用索引定位，见上方注释）
    onChunk: (chunk) => {
      messages.value[assistantIndex].content += chunk
      scrollToBottom()
    },
    // 出错：把占位消息内容替换为错误提示，并标记 error 灰显
    onError: (message) => {
      messages.value[assistantIndex].content = message
      messages.value[assistantIndex].error = true
      reset()
    },
    // 流正常结束：结束接收态
    onDone: () => {
      reset()
    },
  })
}

/** 停止生成：中断当前流式请求 */
function stop() {
  // abort 后 fetch 会抛 AbortError，chatStream 内部静默处理、不触发任何回调，
  // 所以这里必须手动复位状态（否则按钮会一直卡在「停止」）。
  activeController?.abort()
  reset()
}

/**
 * 输入框按键处理：Enter 发送、Shift+Enter 换行。
 *
 * 【为什么用 keydown + preventDefault，而不是表单 submit？】
 * 1. 多行输入框里，表单 submit 不是 Enter 触发的标准行为（原生 textarea 按 Enter 是换行），
 *    依赖表单反而要额外 hack，不如直接在 keydown 里精确判断。
 * 2. keydown 阶段 preventDefault 才能「阻止换行 + 触发发送」二合一；
 *    用 keyup 时换行已经发生，无法再阻止。
 * 3. 避免误触：普通 Enter 是明确的「发送」意图，Shift+Enter 是「换行」意图，
 *    用 event.shiftKey 区分两者，符合主流聊天产品的交互习惯。
 */
function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    // 阻止 Enter 默认的换行行为
    e.preventDefault()
    send()
  }
  // Shift+Enter 不做处理，让浏览器自然换行
}
</script>

<template>
  <div class="chat-page">
    <div class="chat-card">
      <!-- ==================== 消息列表（可滚动） ==================== -->
      <div ref="listRef" class="chat-list">
        <!-- 空态：尚未发送任何消息，显示居中引导 -->
        <div v-if="messages.length === 0" class="empty-state">
          <div class="empty-avatar">
            <el-icon :size="40"><Service /></el-icon>
          </div>
          <p class="empty-title">我是 KnowFlow 智能助手</p>
          <p class="empty-desc">问我对企业知识库的任何问题，我会流式作答</p>
        </div>

        <!-- 消息气泡列表 -->
        <div
          v-for="(msg, index) in messages"
          :key="index"
          class="message-row"
          :class="msg.role"
        >
          <div
            class="bubble"
            :class="[msg.role, { error: msg.error }]"
          >
            {{ msg.content }}
            <!--
              流式接收中的尾部闪烁光标：模拟打字机效果。
              只有当「正在接收」且「本条就是正在接收的那条 assistant 消息」时才显示。
            -->
            <span
              v-if="streaming && msg.role === 'assistant' && index === messages.length - 1"
              class="cursor"
            ></span>
          </div>
        </div>
      </div>

      <!-- ==================== 输入区（固定在底部） ==================== -->
      <div class="input-area">
        <textarea
          v-model="input"
          class="chat-input"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          @keydown="handleKeydown"
        ></textarea>
        <!-- 发送 / 停止按钮随状态切换 -->
        <el-button
          v-if="!streaming"
          type="primary"
          class="send-btn"
          :disabled="!input.trim()"
          @click="send"
        >
          发送
        </el-button>
        <el-button
          v-else
          type="danger"
          class="send-btn"
          @click="stop"
        >
          停止
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  height: 100%;
}

/* 居中卡片：宽约 720px，高度占满内容区，内部 flex 纵向分为「列表 + 输入区」 */
.chat-card {
  max-width: 720px;
  height: 100%;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
  overflow: hidden; /* 让圆角裁剪住内部滚动区 */
}

/* ---------------- 消息列表 ---------------- */
.chat-list {
  flex: 1; /* 占满剩余高度，输入区才固定得住底部 */
  overflow-y: auto;
  padding: 24px;
  background: #fafbfc; /* 极浅灰，与白色气泡形成对比 */
}

/* 空态 */
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

/* 每条消息一行：user 靠右、assistant 靠左 */
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

/* 气泡通用样式 */
.bubble {
  max-width: 78%;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.7;
  /* 保留换行与连续空白（回答是纯文本，可能含换行） */
  white-space: pre-wrap;
  word-break: break-word;
}

/* 用户气泡：靠右、蓝色渐变背景、白字 */
.bubble.user {
  background: linear-gradient(135deg, #409eff, #66b1ff);
  color: #fff;
  border-radius: 16px 16px 4px 16px; /* 右下角收小，像对话气泡的「尾巴」 */
}

/* assistant 气泡：靠左、白底、深色字、细边框 + 轻阴影（在浅灰底上勾勒出卡片感） */
.bubble.assistant {
  background: #ffffff;
  color: #303133;
  border: 1px solid #ebeef5;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  border-radius: 16px 16px 16px 4px; /* 左下角收小 */
}

/* 错误气泡：灰色弱化显示，提示用户这是一条错误而非正常回答 */
.bubble.error {
  color: #909399;
  background: #f5f7fa;
  border-color: #e4e7ed;
  box-shadow: none;
}

/* 尾部闪烁光标：模拟打字机的竖线 */
.cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  background: #409eff;
  vertical-align: text-bottom;
  /* steps 让光标只在「亮/灭」两帧间跳变，而非渐变，更像真实光标 */
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

/* ---------------- 输入区 ---------------- */
.input-area {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid #ebeef5; /* 与列表分隔的层次线 */
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
  max-height: 160px; /* 多行时内部滚动，避免输入区被撑得过高 */
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
