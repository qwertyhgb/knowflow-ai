<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Document, FolderOpened } from '@element-plus/icons-vue'
import { useEnterpriseStore } from '../stores/enterprise'
import { searchDocuments } from '../api/search'
import type { DocumentSearchVO } from '../api/types'

/**
 * 全文搜索页。
 *
 * 在企业内已解析文档中进行关键词全文搜索，展示高亮命中片段，支持分页。
 * 至此，核心闭环（上传 → 解析 → 搜索）全部完成。
 *
 * 【搜索范围天然限定在当前企业？】
 * 搜索接口为企业作用域（路径带 /enterprises/{enterpriseId}），
 * 请求拦截器会自动附加 X-Enterprise-Id 请求头，后端按当前企业 + 用户可见性
 * 过滤 —— 因此搜索到的文档用户均可访问，无需额外鉴权。
 */

const router = useRouter()
const enterpriseStore = useEnterpriseStore()

/** 是否已选定企业 */
const hasEnterprise = computed(() => enterpriseStore.hasEnterprise)

/** 搜索关键词 */
const keyword = ref('')
/** 是否搜索中 */
const searching = ref(false)

/** 是否已执行过一次搜索（用于区分初始态 vs 结果态/空态） */
const hasSearched = ref(false)

/** 搜索结果 */
const resultItems = ref<DocumentSearchVO[]>([])
/** 命中总数 */
const total = ref(0)

// ---------- 分页状态 ----------
/**
 * 【为什么分页状态独立管理？】
 * 搜索本质是"查询参数（keyword）+ 分页状态（page/size）"的组合。
 * 页码或每页条数变化时，关键词不能丢失——如果分页状态不独立，
 * 一旦翻页就重新读取输入框清空关键词，体验会很差。
 * 所以 keyword 存输入框（用户主动提交），page/size 存分页状态（用户翻页时用同一个 keyword 重新搜索）。
 */
const page = ref(1)
const size = ref(10)

onMounted(() => {
  // 无需操作，页面初始态由 hasEnterprise 决定展示引导或搜索界面
})

/** 高亮文件中段里的关键词（ES 高亮核心由后端完成，我们只渲染） */
function renderSnippet(item: DocumentSearchVO): string {
  // 后端可能返回 null、空串、或带 <em> 标签的片段
  return item.contentSnippet || ''
}

/** 根据文件扩展名返回类型图标/描述 */
function fileKind(fileName: string): { type: 'default' | 'success' | 'warning' | 'danger' | 'primary'; label: string } {
  const ext = fileName.split('.').pop()?.toLowerCase() ?? ''
  const map: Record<string, { type: 'success' | 'warning' | 'danger' | 'primary'; label: string }> = {
    pdf: { type: 'danger', label: 'PDF' },
    txt: { type: 'primary', label: 'TXT' },
    md: { type: 'primary', label: 'MD' },
    doc: { type: 'primary', label: 'DOC' },
    docx: { type: 'primary', label: 'DOCX' },
    xls: { type: 'success', label: 'XLS' },
    xlsx: { type: 'success', label: 'XLSX' },
    ppt: { type: 'warning', label: 'PPT' },
    pptx: { type: 'warning', label: 'PPTX' },
    csv: { type: 'success', label: 'CSV' },
    html: { type: 'primary', label: 'HTML' },
  }
  const hit = map[ext]
  return hit ? { type: hit.type, label: hit.label } : { type: 'primary', label: ext ? ext.toUpperCase() : 'FILE' }
}

/** 触发一次搜索 */
async function doSearch() {
  const kw = keyword.value.trim()
  // 【为什么空关键词要前端拦截？】
  // 后端对空关键词返回 400 SEARCH_KEYWORD_REQUIRED。前端先 trim 并提示，
  // 是体验层的拦截——避免用户输入空格就触发一次必然失败的请求。
  // 真正的校验边界仍在后端，即使不发请求，接口本身也会校验。
  if (!kw) {
    ElMessage.warning('请输入搜索关键词')
    return
  }

  searching.value = true
  try {
    const res = await searchDocuments(enterpriseStore.currentEnterpriseId!, {
      keyword: kw,
      page: page.value,
      size: size.value,
    })
    resultItems.value = res.items
    total.value = res.total
    hasSearched.value = true
    // 回到结果区顶部，避免翻页后停留在列表中间位置（体验不连贯）
    window.scrollTo({ top: 0, behavior: 'smooth' })
  } catch {
    // 错误提示已由 http.ts 响应拦截器统一处理
  } finally {
    searching.value = false
  }
}

/** 表单提交：点击搜索按钮或按回车都会触发 */
function handleSearch() {
  // 用户主动提交新关键词搜索时，总是回到第 1 页
  page.value = 1
  doSearch()
}

/** 页码变化 */
function handlePageChange(p: number) {
  page.value = p
  // 分页变化时用当前关键词重新搜索（不丢失 keyword，见上方注释）
  doSearch()
}

/** 每页条数变化 */
function handleSizeChange(s: number) {
  size.value = s
  // 切换每页条数后回到第 1 页（因为当前页可能超出新的总页数，直接设 1 更稳妥）
  page.value = 1
  doSearch()
}

/** 查看文档：跳转到所属知识库详情页 */
function handleView(item: DocumentSearchVO) {
  // 【为什么搜索结果必然可访问？】
  // 后端已按当前企业 + 用户可见性过滤，搜索到的文档用户必有查看权限。
  // 跳转详情页后，即使某些原因遇到 403，KnowledgeBaseDetailView 的现有逻辑
  // 会提示并回退到知识库列表页，不会白屏卡死。
  router.push(`/knowledge-bases/${item.knowledgeBaseId}`)
}

/** 格式化时间：ISO UTC 字符串转本地时间 */
function formatTime(iso: string | undefined): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}
</script>

<template>
  <div class="search-page">
    <!-- ============ 未选定企业：引导卡片 ============ -->
    <template v-if="!hasEnterprise">
      <div class="guide-card">
        <el-icon :size="56" color="#c0c4cc"><FolderOpened /></el-icon>
        <h3 class="guide-title">请先选择企业</h3>
        <p class="guide-desc">搜索范围是企业内已解析的文档，请先在企业管理中选择要操作的企业。</p>
        <el-button type="primary" @click="router.push('/enterprise')">
          前往企业管理
        </el-button>
      </div>
    </template>

    <!-- ============ 已选定企业：搜索界面 ============ -->
    <template v-else>
      <!-- 搜索区：大号输入框 + 按钮，点击或回车触发 -->
      <div class="search-area">
        <h1 class="search-heading">搜索企业文档</h1>
        <el-form class="search-form" native-type="submit" @submit.prevent="handleSearch">
          <el-input
            v-model="keyword"
            size="large"
            placeholder="搜索企业内文档，如：Redis 缓存设计"
            clearable
            :prefix-icon="Search"
            class="search-input"
            @keyup.enter="handleSearch"
          />
          <el-button
            type="primary"
            size="large"
            class="search-btn"
            :loading="searching"
            @click="handleSearch"
          >
            {{ searching ? '搜索中…' : '搜索' }}
          </el-button>
        </el-form>
      </div>

      <!-- ============ 结果区 ============ -->
      <div class="result-area">
        <!-- 初始态（从未搜索） -->
        <div v-if="!hasSearched && !searching" class="placeholder">
          <el-icon :size="48" color="#c0c4cc"><Search /></el-icon>
          <p>输入关键词，搜索企业内已解析的文档内容</p>
        </div>

        <!-- 结果态 -->
        <template v-else-if="hasSearched && !searching">
          <!-- 结果为空 -->
          <el-empty v-if="total === 0" description="未找到相关文档，换个关键词试试" />

          <!-- 有结果 -->
          <template v-else>
            <div class="result-summary">共命中 {{ total }} 条结果</div>

            <div class="result-list">
              <div
                v-for="item in resultItems"
                :key="item.documentId"
                class="result-card"
                @click="handleView(item)"
              >
                <!-- 文件名 + 类型图标 -->
                <div class="card-head">
                  <el-tag :type="fileKind(item.fileName).type" size="small" effect="dark" class="file-type">
                    {{ fileKind(item.fileName).label }}
                  </el-tag>
                  <span class="file-name">{{ item.fileName }}</span>
                </div>

                <!-- 高亮片段 -->
                <!--
                  【为什么 snippet 用 v-html 渲染？】
                  后端返回的 contentSnippet 已包含 Elasticsearch 高亮标签 <em> 包裹命中词
                  （如 "<em>Redis</em> 缓存设计"）。如果只用 {{ }} 插值渲染，
                  <em> 会被当作纯文本显示出来（看到一堆尖括号），无法呈现高亮效果。
                  因此必须用 v-html 让浏览器把 <em> 解析为 DOM 标签，再通过 CSS 给它高亮样式。

                  【安全提示】v-html 会原样渲染传入的 HTML，理论上存在 XSS 风险。
                  这里的缓解因素：
                  1. 内容来自企业内部文档（可信来源）而非用户自由输入的外部链接；
                  2. 名称/路径来自后端，风险相对可控。
                  正规做法是：对 v-html 内容做 HTML 净化白名单（只允许 em 等少数标签），
                  这是后续安全学习主题，此处先用 v-html + 最小化输出。
                -->
                <div class="snippet" v-html="renderSnippet(item)"></div>

                <!-- 辅助信息 -->
                <div class="card-meta">
                  <span class="meta-item">文档 ID：{{ item.documentId }}</span>
                  <span class="meta-item">知识库 ID：{{ item.knowledgeBaseId }}</span>
                  <span class="meta-time">创建于 {{ formatTime(item.createdAt) }}</span>
                </div>

                <el-button
                  type="primary"
                  link
                  :icon="Document"
                  class="view-btn"
                  @click.stop="handleView(item)"
                >
                  查看
                </el-button>
              </div>
            </div>

            <!-- 分页 -->
            <div class="pagination-wrap">
              <el-pagination
                background
                layout="total, sizes, prev, pager, next"
                :total="total"
                :page-size="size"
                :current-page="page"
                :page-sizes="[10, 20, 50]"
                @current-change="handlePageChange"
                @size-change="handleSizeChange"
              />
            </div>
          </template>
        </template>

        <!-- 搜索中 -->
        <div v-if="searching" class="searching-placeholder">
          <el-icon class="is-loading" :size="48" color="#409eff"><Search /></el-icon>
          <p>正在搜索…</p>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.search-page {
  max-width: 860px;
  margin: 0 auto;
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

/* ---------- 搜索区 ---------- */
.search-area {
  text-align: center;
  margin-bottom: 32px;
}

.search-heading {
  font-size: 24px;
  font-weight: 600;
  color: #1f2d3d;
  margin-bottom: 20px;
}

.search-form {
  display: flex;
  gap: 12px;
  max-width: 720px;
  margin: 0 auto;
}

.search-input {
  flex: 1;
}

.search-btn {
  flex-shrink: 0;
}

/* ---------- 结果区 ---------- */
.result-area {
  min-height: 300px;
}

/* 初始态/空态/搜索中占位 */
.placeholder,
.searching-placeholder {
  text-align: center;
  padding: 60px 20px;
  color: #c0c4cc;
  font-size: 14px;
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.searching-placeholder {
  margin-top: 20px;
}

/* 结果总数 */
.result-summary {
  font-size: 14px;
  color: #909399;
  margin-bottom: 16px;
}

/* 结果卡片列表 */
.result-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.result-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  position: relative;
}

.result-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 20px rgba(0, 0, 0, 0.08);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.file-type {
  font-weight: 600;
  letter-spacing: 0.5px;
}

.file-name {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

/* 高亮片段 */
.snippet {
  font-size: 13px;
  line-height: 1.7;
  color: #606266;
  margin-bottom: 12px;
}

/* 命中词高亮样式：蓝色字 + 浅蓝底 */
.snippet :deep(em) {
  font-style: normal; /* 去掉默认斜体，避免斜体影响阅读 */
  background: #ecf5ff; /* 浅蓝底 */
  color: #409eff; /* 蓝色字 */
  padding: 0 2px;
  border-radius: 3px;
  font-weight: 600;
}

.card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  font-size: 12px;
  color: #c0c4cc;
}

.view-btn {
  position: absolute;
  right: 20px;
  bottom: 18px;
  font-weight: 500;
}

/* ---------- 分页 ---------- */
.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>