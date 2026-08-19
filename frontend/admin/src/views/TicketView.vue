<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Tickets } from '@element-plus/icons-vue'
import { useEnterpriseStore } from '../stores/enterprise'
import { createTicket, listMyTickets } from '../api/ticket'
import type { TicketCategory, TicketPriority, TicketVO } from '../api/ticket'

/**
 * 工单列表页（我的工单）。
 *
 * 工单从属于当前企业：访问此页面时，
 * - 未选定企业 → 引导用户先选择企业；
 * - 已选定企业 → 展示「我提交的工单」列表，可创建新工单。
 *
 * 【为什么这里是「我的工单」而不是「全部工单」？】
 * 本步对应后端「列表只返回当前用户提交的工单」这一语义（客服视角的「全部工单」
 * 是后续步骤）。前端按后端接口能力如实呈现，标题用「我的工单」避免误导。
 */

const router = useRouter()
const enterpriseStore = useEnterpriseStore()

/** 是否已选定企业 */
const hasEnterprise = computed(() => enterpriseStore.hasEnterprise)

/** 工单列表 */
const list = ref<TicketVO[]>([])
/** 列表加载中 */
const loading = ref(false)

// ---------- 创建工单对话框 ----------
const dialogVisible = ref(false)
const creating = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  title: '',
  description: '',
  category: '' as TicketCategory | '',
  /** 默认优先级为 MEDIUM（中等）——大多数工单不是紧急问题，避免用户每次都点紧急。 */
  priority: 'MEDIUM' as TicketPriority,
})

/** 表单校验规则：与后端约束对齐（title ≤200、description ≤5000、分类必填）。 */
const rules: FormRules<typeof form> = {
  title: [
    { required: true, message: '请输入工单标题', trigger: 'blur' },
    { max: 200, message: '标题长度不能超过200', trigger: 'blur' },
  ],
  description: [
    { required: true, message: '请输入工单描述', trigger: 'blur' },
    { max: 5000, message: '描述长度不能超过5000', trigger: 'blur' },
  ],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }],
}

/** 分类选项：值 → 中文文案 */
const categoryOptions: { value: TicketCategory; label: string }[] = [
  { value: 'CONSULTATION', label: '咨询' },
  { value: 'ISSUE', label: '问题反馈' },
  { value: 'FEEDBACK', label: '建议' },
  { value: 'OTHER', label: '其他' },
]

/** 优先级选项：值 → 中文文案 */
const priorityOptions: { value: TicketPriority; label: string }[] = [
  { value: 'LOW', label: '低' },
  { value: 'MEDIUM', label: '中' },
  { value: 'HIGH', label: '高' },
  { value: 'URGENT', label: '紧急' },
]

onMounted(() => {
  if (hasEnterprise.value) {
    loadList()
  }
})

/** 拉取「我的工单」列表 */
async function loadList() {
  const id = enterpriseStore.currentEnterpriseId
  if (id === null) return
  loading.value = true
  try {
    list.value = await listMyTickets(id)
  } catch {
    // 错误提示已由 http.ts 响应拦截器统一处理
  } finally {
    loading.value = false
  }
}

/** 提交创建工单 */
async function handleCreate() {
  await formRef.value?.validate(async (valid) => {
    if (!valid) return
    creating.value = true
    try {
      await createTicket(enterpriseStore.currentEnterpriseId!, {
        title: form.title,
        description: form.description,
        category: form.category as TicketCategory,
        priority: form.priority,
      })
      // 【为什么文案是「AI 正在尝试解答」而不是「创建成功」？】
      // 创建工单时后端会同步触发一次 AI 自动回答（有知识库依据则回答带引用、
      // 无依据则转人工提示）。这个提示让用户知道「工单已受理，AI 正在帮你看」，
      // 引导他去详情页查看 AI 的自动回答结果，而不是停留在「提交完就结束」的印象。
      ElMessage.success('工单已创建，AI 正在尝试解答')
      dialogVisible.value = false
      resetForm()
      await loadList()
    } catch {
      // 错误提示已由 http.ts 统一处理
    } finally {
      creating.value = false
    }
  })
}

/** 创建成功后重置表单（避免下次打开对话框残留上次输入） */
function resetForm() {
  form.title = ''
  form.description = ''
  form.category = ''
  form.priority = 'MEDIUM'
}

/** 进入工单详情 */
function handleView(ticket: TicketVO) {
  router.push(`/tickets/${ticket.id}`)
}

// ---------- 展示辅助映射 ----------

/** 分类中文映射 */
const categoryLabelMap: Record<TicketCategory, string> = {
  CONSULTATION: '咨询',
  ISSUE: '问题反馈',
  FEEDBACK: '建议',
  OTHER: '其他',
}
function categoryLabel(c: TicketCategory): string {
  return categoryLabelMap[c] ?? c
}

/** 优先级 tag 类型（四色语义：低灰 / 中蓝 / 高橙 / 紧急红） */
const priorityTagMap: Record<TicketPriority, 'info' | 'primary' | 'warning' | 'danger'> = {
  LOW: 'info',
  MEDIUM: 'primary',
  HIGH: 'warning',
  URGENT: 'danger',
}
function priorityTag(p: TicketPriority): 'info' | 'primary' | 'warning' | 'danger' {
  return priorityTagMap[p] ?? 'info'
}
/** 优先级中文映射 */
const priorityLabelMap: Record<TicketPriority, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  URGENT: '紧急',
}
function priorityLabel(p: TicketPriority): string {
  return priorityLabelMap[p] ?? p
}

/** 状态中文映射（五态） */
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
/** 状态 tag 类型（五态：待处理/已关闭灰、已分配蓝、处理中橙、已解决绿） */
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
  <div class="ticket-page">
    <!-- ============ 未选定企业：引导提示 ============ -->
    <template v-if="!hasEnterprise">
      <div class="guide-card">
        <el-icon :size="56" color="#c0c4cc"><Tickets /></el-icon>
        <h3 class="guide-title">请先选择企业</h3>
        <p class="guide-desc">工单从属于企业，请先在企业管理中选择要操作的企业。</p>
        <el-button type="primary" @click="router.push('/enterprise')">
          前往企业管理
        </el-button>
      </div>
    </template>

    <!-- ============ 已选定企业：工单列表 ============ -->
    <template v-else>
      <div class="list-head">
        <div class="list-head-left">
          <h2 class="list-title">我的工单</h2>
          <el-tag type="primary" effect="light" size="small" class="ent-tag">
            {{ enterpriseStore.currentEnterpriseName }}
          </el-tag>
        </div>
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">
          创建工单
        </el-button>
      </div>

      <!-- 工单表格 -->
      <div class="section-card">
        <el-table
          v-loading="loading"
          :data="list"
          stripe
          style="width: 100%"
          :header-cell-style="{ background: '#fafafa', color: '#606266' }"
        >
          <!-- 标题 -->
          <el-table-column label="标题" min-width="220">
            <template #default="{ row }: { row: TicketVO }">
              <span class="ticket-title">{{ row.title }}</span>
            </template>
          </el-table-column>

          <!-- 分类 -->
          <el-table-column label="分类" width="110">
            <template #default="{ row }: { row: TicketVO }">
              <span class="plain-text">{{ categoryLabel(row.category) }}</span>
            </template>
          </el-table-column>

          <!-- 优先级（四色 tag） -->
          <el-table-column label="优先级" width="100">
            <template #default="{ row }: { row: TicketVO }">
              <el-tag :type="priorityTag(row.priority)" size="small" effect="light">
                {{ priorityLabel(row.priority) }}
              </el-tag>
            </template>
          </el-table-column>

          <!-- 状态（五态 tag） -->
          <el-table-column label="状态" width="110">
            <template #default="{ row }: { row: TicketVO }">
              <el-tag :type="statusTag(row.status)" size="small" effect="light">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>

          <!-- 创建时间 -->
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }: { row: TicketVO }">
              <span class="time-text">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>

          <!-- 操作 -->
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }: { row: TicketVO }">
              <el-button type="primary" link size="small" @click="handleView(row)">
                查看
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 空态 -->
        <el-empty
          v-if="!loading && list.length === 0"
          description="还没有工单，遇到问题就提交一个吧"
        />
      </div>
    </template>

    <!-- ============ 创建工单对话框 ============ -->
    <el-dialog v-model="dialogVisible" title="创建工单" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="标题" prop="title">
          <el-input
            v-model="form.title"
            placeholder="一句话概括问题"
            clearable
            maxlength="200"
          />
        </el-form-item>

        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            placeholder="请详细描述遇到的问题（AI 将基于此在知识库中检索解答）"
            maxlength="5000"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="分类" prop="category">
          <el-select
            v-model="form.category"
            placeholder="请选择分类"
            style="width: 100%"
          >
            <el-option
              v-for="opt in categoryOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="优先级" prop="priority">
          <el-select v-model="form.priority" style="width: 100%">
            <el-option
              v-for="opt in priorityOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">
          提交工单
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ticket-page {
  max-width: 1080px;
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

/* ---------- 列表头部 ---------- */
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.list-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.list-title {
  font-size: 20px;
  font-weight: 600;
  color: #1f2d3d;
}

.ent-tag {
  font-size: 12px;
}

/* ---------- 表格卡片 ---------- */
.section-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
}

.ticket-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.plain-text {
  font-size: 13px;
  color: #606266;
}

.time-text {
  font-size: 13px;
  color: #909399;
}
</style>