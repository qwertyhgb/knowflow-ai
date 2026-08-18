<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { DocumentVO, KnowledgeBaseMemberRole } from '../../api/types'
import {
  uploadDocument,
  getDocuments,
  downloadDocument,
  parseDocument,
} from '../../api/document'
/**
 * 文档管理组件。
 *
 * 功能：拖拽上传、文档列表、下载、手动解析（带轮询）。
 * 权限：仅 EDITOR / ADMIN 可上传和解析，VIEWER 只读（可下载）。
 *
 * 【props 设计原因】
 * 父组件（KnowledgeBaseDetailView）已经加载了知识库详情和企业上下文，
 * 直接透传 knowledgeBaseId、enterpriseId 和 myRole 即可，避免组件内重复请求。
 */

const props = defineProps<{
  /** 知识库 ID（从路由参数获取） */
  knowledgeBaseId: number
  /** 当前企业 ID（从企业 store 获取） */
  enterpriseId: number
  /** 当前用户在知识库中的角色；非成员时为 null */
  myRole: KnowledgeBaseMemberRole | null
}>()

// ============================================================
// 文档列表
// ============================================================

const documents = ref<DocumentVO[]>([])
const tableLoading = ref(false)

/** 刷新文档列表 */
async function loadDocuments() {
  tableLoading.value = true
  try {
    documents.value = await getDocuments(props.enterpriseId, props.knowledgeBaseId)
  } catch {
    // 错误提示已由 http.ts 统一处理
  } finally {
    tableLoading.value = false
  }
}

onMounted(() => {
  loadDocuments()
})

// ============================================================
// 拖拽上传
// ============================================================

/** 拖拽区域悬浮态 */
const dragging = ref(false)

/** 上传中（禁用按钮防止重复提交） */
const uploading = ref(false)

/**
 * 处理文件上传（包括拖拽和文件选择两种触发方式）。
 *
 * 【为什么上传后不调 loadDocuments() 一次？】
 * 因为后端上传成功后自动入队解析，状态可能已经变为 PARSING，
 * 而解析是异步的，需要轮询才能知道最终结果。所以直接用轮询机制刷新。
 */
async function handleFileChange(_event: Event) {
  // _event 是 input change 事件，实际文件从 input.files 获取
  const input = (_event.target as HTMLInputElement)
  const file = input.files?.[0]
  if (!file) return

  // 清理 input 值，允许重复选择同一文件
  input.value = ''

  await uploadFile(file)
}

/**
 * 处理拖拽放置。
 *
 * 【为什么从 e.dataTransfer.files 取文件？】
 * 拖拽文件时，浏览器不会触发 input 的 change 事件，
 * 而是将文件列表挂在 drag event 的 dataTransfer.files 上。
 */
function handleDrop(e: DragEvent) {
  dragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (!file) return
  e.dataTransfer?.clearData()
  uploadFile(file)
}

/** 允许上传的文件扩展名白名单（与后端解析能力对齐） */
const ALLOWED_EXTENSIONS = ['pdf', 'docx', 'txt', 'md']

/** 单文件大小上限：10MB */
const MAX_FILE_SIZE = 10 * 1024 * 1024

/**
 * 实际执行文件上传。
 *
 * 【为什么前端要做类型/大小校验？】
 * 这是体验层拦截：不合法的文件直接在前端拒绝并提示，
 * 用户无需等待一次注定失败的网络往返。真正的安全边界仍在后端
 * （multipart 大小限制 + 白名单校验），前端校验不能替代后端校验。
 *
 * 【FormData 为什么不用设置 Content-Type？】
 * 浏览器检测到 FormData 对象时，会自动设置 Content-Type 为
 * multipart/form-data; boundary=----WebKitFormBoundaryxxxxx，
 * 其中 boundary 是随机生成的分隔符。如果手动设置 Content-Type
 * 为 multipart/form-data 而不带 boundary，后端无法解析文件。
 */
async function uploadFile(file: File) {
  // 扩展名白名单校验：取文件名最后一个点后的部分，小写比较
  const ext = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (!ALLOWED_EXTENSIONS.includes(ext)) {
    ElMessage.warning('仅支持 pdf/docx/txt/md 文件')
    return
  }

  // 大小校验：file.size 单位是字节
  if (file.size > MAX_FILE_SIZE) {
    ElMessage.warning('文件大小不能超过 10MB')
    return
  }

  uploading.value = true
  try {
    // 拦截器已解包，result 就是新文档的 DocumentVO 本体
    const result = await uploadDocument(props.enterpriseId, props.knowledgeBaseId, file)
    ElMessage.success(`"${file.name}" 上传成功，正在解析...`)
    // 新文档插入列表头部，让用户立即看到上传结果
    documents.value.unshift(result)
    // 以刚上传文档自己的 ID 为轮询目标，跟踪它的解析状态变化
    pollDocumentStatus(result.id)
  } catch {
    // 错误提示已由 http.ts 统一处理
  } finally {
    uploading.value = false
  }
}

// ============================================================
// 文件下载
// ============================================================

/**
 * 下载文件。
 *
 * 【Blob + createObjectURL 下载原理】
 * 1. axios 以 responseType: 'blob' 接收二进制数据，res.data 是 Blob 对象
 * 2. URL.createObjectURL() 为 Blob 创建一个临时 blob:URL（如 blob:http://xxx/yyy）
 * 3. 创建一个 <a> 元素，设置 href 为 blob:URL，download 为建议文件名
 * 4. 模拟点击 <a> 触发浏览器下载
 * 5. 下载完成后 revokeObjectURL() 释放临时 URL，防止内存泄漏
 *
 * 【为什么用 revokeObjectURL？】
 * 每次 createObjectURL 都会占用内存，如果不释放，频繁下载会导致内存持续增长。
 * revokeObjectURL 在"下载触发后"调用即可，不需要等待下载完成（浏览器已缓存）。
 */
async function handleDownload(doc: DocumentVO) {
  try {
    const res = await downloadDocument(
      props.enterpriseId,
      props.knowledgeBaseId,
      doc.id,
    )
    // 从 Content-Disposition 头提取文件名，兼容中文（RFC 5987 filename*=UTF-8''...）
    const fileName = extractFileName(res.headers['content-disposition'], doc.fileName)
    // 创建临时 URL 并触发下载
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.style.display = 'none'
    a.href = url
    a.download = fileName
    document.body.appendChild(a)
    a.click()
    // 清理：从 DOM 移除 + 释放 Blob URL
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch {
    // 错误提示已由 http.ts 统一处理
  }
}

/**
 * 从 Content-Disposition 头提取建议文件名。
 *
 * 【为什么需要解析 Content-Disposition？】
 * 后端通过 response.setHeader("Content-Disposition", ...) 告知浏览器下载文件名，
 * 因为 API 响应是 Blob 流，不是 JSON，文件名不在响应体中，只能通过 header 传递。
 *
 * 【RFC 5987 filename*=UTF-8''... 是什么？】
 * 标准 filename="xxx" 不支持非 ASCII 字符（如中文）。
 * RFC 5987 定义了 filename*=UTF-8''%E4%B8%AD%E6%96%87 编码格式，
 * 其中 %E4%B8%AD%E6%96%87 是 "中文" 的 UTF-8 十六进制编码。
 * 浏览器解析此格式后可正确还原中文文件名。
 *
 * @param contentDisposition - Content-Disposition header 值
 * @param fallback - 如果解析失败，使用文档列表中的 fileName 作为回退
 * @returns 解析后的文件名
 */
function extractFileName(contentDisposition: string | undefined, fallback: string): string {
  if (!contentDisposition) return fallback

  // 先尝试 RFC 5987 编码格式：filename*=UTF-8''%E4%B8%AD%E6%96%87
  const utf8Match = contentDisposition.match(/filename\*=UTF-8''(.+?)(?:;|$)/i)
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1])
    } catch {
      // decode 失败则回退到 fallback
    }
  }

  // 回退到传统 filename="xxx" 格式
  const match = contentDisposition.match(/filename="([^"]+)"/i)
  if (match?.[1]) {
    return match[1]
  }

  return fallback
}

// ============================================================
// 手动触发解析 + 短轮询
// ============================================================

/** 正在轮询某个文档的解析状态（取消轮询用） */
let pollTimer: ReturnType<typeof setInterval> | null = null

/**
 * 手动触发文档解析，并启动短轮询等待状态变更。
 *
 * 【短轮询为什么是必要妥协？】
 * 后端解析是异步的（消息队列 + 工作消费者模型），前端 POST 触发后
 * 无法立刻知道结果。WebSocket/SSE 是最优解，但项目初期还没有这些基础设施。
 * 短轮询（每 3 秒查一次）是实现简单、可靠性高的替代方案——
 * 缺点是浪费请求（即使没变化也轮询），但在内网 SaaS 场景下可接受。
 *
 * @param documentId - 要轮询的文档 ID
 */
function pollDocumentStatus(documentId: number) {
  // 如果已有轮询在运行，先取消（防止叠加）
  if (pollTimer !== null) {
    clearInterval(pollTimer)
  }

  let count = 0
  const maxCount = 10 // 最多轮询 10 次 = 30 秒

  pollTimer = setInterval(async () => {
    count++
    try {
      const docs = await getDocuments(props.enterpriseId, props.knowledgeBaseId)
      const doc = docs.find((d) => d.id === documentId)
      if (!doc) {
        // 文档突然消失，停止轮询
        stopPolling()
        return
      }

      // READY 或 FAILED 都是终态，轮询结束
      if (doc.status === 'READY' || doc.status === 'FAILED') {
        stopPolling()
        documents.value = docs
        if (doc.status === 'READY') {
          ElMessage.success('文档解析完成')
        } else {
          ElMessage.error('文档解析失败，请检查文件格式后重新上传')
        }
        return
      }

      // 中间状态（UPLOADED / PARSING），更新列表但不提示
      documents.value = docs
    } catch {
      // 轮询期间网络错误，停止轮询避免无限重试
      stopPolling()
    }

    if (count >= maxCount) {
      stopPolling()
      ElMessage.warning('文档解析超时，请稍后手动刷新查看状态')
    }
  }, 3000)
}

/** 清除轮询定时器 */
function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 手动触发解析 */
async function handleParse(doc: DocumentVO) {
  try {
    await parseDocument(props.enterpriseId, props.knowledgeBaseId, doc.id)
    ElMessage.info('正在重新解析...')
    // 启动轮询等待解析完成
    pollDocumentStatus(doc.id)
  } catch {
    // 错误提示已由 http.ts 统一处理
  }
}

// 组件卸载时清理定时器，防止内存泄漏
onUnmounted(() => {
  stopPolling()
})

// ============================================================
// 权限与格式化
// ============================================================

/**
 * 是否有编辑权限（EDITOR 或 ADMIN）。
 * 用于控制上传区域和解析按钮的可见性。
 *
 * 【为什么 VIEWER 不可上传/解析？】
 * 知识库采用资源级角色模型：EDITOR 及以上才有写能力，
 * VIEWER 只能读取（查看列表、下载文件）。
 *
 * 【myRole 为 null 时怎么办？】
 * 企业 OWNER/ADMIN 访问未加入的知识库时 myRole 可能为 null，
 * 此时按无编辑权限处理——前端按角色显隐只是体验层，
 * 真正的权限校验以后端接口返回为准（无权限会返回 403）。
 */
const hasEditPermission = computed(() => {
  return props.myRole === 'EDITOR' || props.myRole === 'ADMIN'
})

defineExpose({ hasEditPermission })

/** 格式化文件大小（字节 → KB/MB/GB） */
function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.floor(Math.log(bytes) / Math.log(1024))
  const value = bytes / Math.pow(1024, index)
  return `${index === 0 ? value : value.toFixed(2)} ${units[index]}`
}

/** 格式化时间 */
function formatTime(iso: string | undefined | null): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

/** 文档状态标签类型映射 */
const statusTagConfig: Record<string, { type: 'success' | 'warning' | 'danger' | 'info'; label: string }> = {
  READY: { type: 'success', label: '就绪' },
  UPLOADED: { type: 'info', label: '待解析' },
  PARSING: { type: 'warning', label: '解析中' },
  FAILED: { type: 'danger', label: '解析失败' },
}
</script>

<template>
  <div class="doc-management">
    <!-- ========== 上传区域（仅 EDITOR/ADMIN 可见） ========== -->
    <div
      v-if="hasEditPermission"
      class="upload-area"
      :class="{ dragging: dragging }"
      @dragenter.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @dragover.prevent
      @drop="handleDrop"
    >
      <!--
        同时支持拖拽和点击选择两种上传方式：
        - 拖拽：通过 dragenter/dragleave/drop 事件管理视觉状态
        - 点击：隐藏的文件选择 input，点击按钮触发 click()
      -->
      <el-icon :size="32" color="#409eff">
        <svg viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg">
          <path fill="currentColor" d="M544 600V168h-64v432H192v64h640v-64zM288 744h448l-168-168 45-45 200 200-200 200-45-45 168-168H288z"/>
        </svg>
      </el-icon>
      <p class="upload-text">拖拽文件到此处，或</p>
      <el-button
        type="primary"
        :loading="uploading"
        @click="uploading && false"
      >
        点击选择文件
      </el-button>
      <!-- 隐藏的文件选择 input：change 事件触发实际上传逻辑 -->
      <input
        type="file"
        class="file-input"
        @change="handleFileChange"
      />
      <p class="upload-hint">支持 pdf/docx/txt/md，单个文件不超过 10MB</p>
    </div>

    <!-- ========== 文档列表 ========== -->
    <div class="doc-list">
      <h3 class="list-title">
        文档列表（{{ documents.length }}）
      </h3>

      <el-table
        v-loading="tableLoading"
        :data="documents"
        stripe
        :header-cell-style="{ background: '#fafafa', color: '#606266' }"
      >
        <el-table-column prop="fileName" label="文件名" min-width="200">
          <template #default="{ row }">
            <span class="file-name">{{ row.fileName }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="contentType" label="类型" width="140">
          <template #default="{ row }">
            <span class="content-type">{{ row.contentType }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="fileSize" label="大小" width="110">
          <template #default="{ row }">
            <span class="file-size">{{ formatFileSize(row.fileSize) }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag
              :type="statusTagConfig[row.status]?.type ?? 'info'"
              size="small"
            >
              {{ statusTagConfig[row.status]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="createdAt" label="上传时间" width="180">
          <template #default="{ row }">
            <span class="time-text">{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleDownload(row)">
              下载
            </el-button>
            <!--
              【解析按钮的显示规则——与后端状态机对齐】
              后端状态机：UPLOADED → PARSING → READY/FAILED。
              手动解析接口仅接受 UPLOADED 状态（其他状态返回 409）：
              - READY 已就绪，无需再解析
              - FAILED 是终态不可恢复，只能重新上传
              - PARSING 正在解析中，重复触发无意义
              所以该按钮是补偿入口：正常流程上传后自动入队解析，
              只有异步消息发布失败等异常导致文档滞留在 UPLOADED 时才需要手动触发。
            -->
            <el-button
              v-if="hasEditPermission && row.status === 'UPLOADED'"
              type="warning"
              link
              size="small"
              @click="handleParse(row)"
            >
              解析
            </el-button>
            <span v-if="row.status === 'PARSING'" class="no-action">解析中...</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空状态 -->
      <el-empty
        v-if="!tableLoading && documents.length === 0"
        :description="hasEditPermission ? '暂无文档，上传文件开始使用' : '暂无文档'"
        :image-size="80"
      />
    </div>
  </div>
</template>

<style scoped>
.doc-management {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ---------- 上传区域 ---------- */
.upload-area {
  border: 2px dashed #dcdfe6;
  border-radius: 12px;
  padding: 40px 20px;
  text-align: center;
  background: #fafafa;
  transition: border-color 0.2s ease, background-color 0.2s ease;
  cursor: pointer;
  position: relative;
}

.upload-area.dragging {
  border-color: #409eff;
  background: #ecf5ff;
}

.upload-area:hover:not(.dragging) {
  border-color: #c0c4cc;
}

.upload-text {
  font-size: 14px;
  color: #606266;
  margin: 12px 0 0;
}

.upload-hint {
  font-size: 12px;
  color: #909399;
  margin: 8px 0 0;
}

/* 隐藏的文件 input：占据整个上传区域，点击任何位置触发 */
.file-input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

/* ---------- 文档列表 ---------- */
.list-title {
  font-size: 15px;
  font-weight: 600;
  color: #1f2d3d;
  margin: 0;
}

.file-name {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.content-type {
  font-size: 12px;
  color: #909399;
}

.file-size {
  font-size: 13px;
  color: #606266;
}

.time-text {
  font-size: 13px;
  color: #606266;
}

.no-action {
  font-size: 13px;
  color: #c0c4cc;
}
</style>
