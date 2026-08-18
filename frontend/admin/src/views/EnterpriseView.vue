<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { useEnterpriseStore } from '../stores/enterprise'
import {
  createEnterprise,
  getEnterpriseDetail,
  getMyEnterprises,
} from '../api/enterprise'
import type { EnterpriseVO } from '../api/types'
import MemberManagement from './enterprise/MemberManagement.vue'
import InvitationManagement from './enterprise/InvitationManagement.vue'

/**
 * 企业管理页。
 *
 * 核心逻辑围绕"企业上下文"展开：
 * - 未选定企业时：展示「我的企业」列表，可创建企业、点击「进入」选定某个企业。
 * - 已选定企业时：使用 el-tabs 页签展示三个子视图——企业信息、成员管理、邀请管理。
 *
 * 【为什么用页签而不是拆多个路由？】
 * 成员和邀请都是"当前企业"的从属功能，在同一个页面内用页签切换，
 * 用户能直观感知到"我仍然在这个企业里操作"，上下文一目了然。
 * 路由（如 /enterprise/members）用于跨域跳转，而这里的三个页签是同域内的视图切换。
 */

const enterpriseStore = useEnterpriseStore()

/** 列表加载中 */
const loading = ref(false)
/** 我的企业列表 */
const enterprises = ref<EnterpriseVO[]>([])

/** 状态：是否已选定企业（由 store 上下文决定） */
const hasEnterprise = computed(() => enterpriseStore.hasEnterprise)

/** 当前企业详情（已选定企业时拉取并展示） */
const detail = ref<EnterpriseVO | null>(null)
/** 详情加载中 */
const detailLoading = ref(false)

/** 当前激活的页签 */
const activeTab = ref('info')

// ---------- 创建企业对话框 ----------
const dialogVisible = ref(false)
const creating = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({ name: '' })
const rules: FormRules = {
  name: [
    { required: true, message: '请输入企业名称', trigger: 'blur' },
    { max: 100, message: '企业名称长度不能超过100', trigger: 'blur' },
  ],
}

/**
 * 页面加载逻辑：
 * 已选定企业 → 拉详情；未选定 → 拉列表。
 */
onMounted(() => {
  if (hasEnterprise.value) {
    loadDetail()
  } else {
    loadList()
  }
})

/**
 * 监听企业上下文变化：如果用户通过退出企业操作清除了上下文，
 * 自动切换回列表视图。
 */
watch(
  () => enterpriseStore.hasEnterprise,
  (has) => {
    if (!has) {
      detail.value = null
      loadList()
    }
  },
)

/** 拉取我的企业列表 */
async function loadList() {
  loading.value = true
  try {
    enterprises.value = await getMyEnterprises()
  } catch {
    // 错误提示已由 http.ts 响应拦截器统一处理
  } finally {
    loading.value = false
  }
}

/** 拉取当前企业详情 */
async function loadDetail() {
  const id = enterpriseStore.currentEnterpriseId
  if (id === null) return
  detailLoading.value = true
  try {
    detail.value = await getEnterpriseDetail(id)
    // 以接口返回为准，同步刷新顶部栏展示的企业名
    enterpriseStore.setCurrentEnterpriseName(detail.value.name)
  } catch {
    // 拉取失败（如已不是该企业成员返回 403），清除上下文回列表
    enterpriseStore.clearCurrentEnterpriseId()
    loadList()
  } finally {
    detailLoading.value = false
  }
}

/** 提交创建企业 */
async function handleCreate() {
  await formRef.value?.validate(async (valid) => {
    if (!valid) return
    creating.value = true
    try {
      await createEnterprise({ name: form.name })
      ElMessage.success('企业创建成功')
      dialogVisible.value = false
      form.name = ''
      // 刷新列表，让新企业卡片出现
      await loadList()
    } catch {
      // 错误提示已由 http.ts 统一处理
    } finally {
      creating.value = false
    }
  })
}

/**
 * 进入企业：选定该企业，然后留在本页展示详情。
 * setCurrentEnterpriseId 会持久化到 localStorage，刷新后上下文不丢；
 * 随后 loadDetail() 发出的详情请求自动携带 X-Enterprise-Id，能拿到数据即证明头生效。
 */
function handleEnter(enterprise: EnterpriseVO) {
  enterpriseStore.setCurrentEnterpriseId(enterprise.id)
  enterpriseStore.setCurrentEnterpriseName(enterprise.name)
  ElMessage.success(`已进入「${enterprise.name}」`)
  loadDetail()
}

/** 离开当前企业：清除上下文后回到列表视图 */
function handleLeave() {
  enterpriseStore.clearCurrentEnterpriseId()
  detail.value = null
  ElMessage.info('已离开当前企业')
  loadList()
}

/** 创建时间：ISO UTC 字符串转本地时间，仅展示层转换 */
function formatTime(iso: string | undefined): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

/** 企业卡片渐变图标的背景：按企业 ID 取不同色系，让列表更生动 */
const gradients = [
  'linear-gradient(135deg, #409eff, #66b1ff)',
  'linear-gradient(135deg, #67c23a, #85ce61)',
  'linear-gradient(135deg, #e6a23c, #ebb563)',
  'linear-gradient(135deg, #f56c6c, #f78989)',
  'linear-gradient(135deg, #909399, #a8abb2)',
]
function iconBg(id: number): string {
  return gradients[id % gradients.length]
}
</script>

<template>
  <div class="enterprise">
    <!-- ============ 已选定企业：页签视图 ============ -->
    <template v-if="hasEnterprise">
      <el-tabs v-model="activeTab" class="enterprise-tabs">
        <!-- 企业信息页签 -->
        <el-tab-pane label="企业信息" name="info">
          <div v-loading="detailLoading" class="detail-card">
            <template v-if="detail">
              <div class="detail-head">
                <span class="detail-icon" :style="{ background: iconBg(detail.id) }">
                  {{ detail.name[0] }}
                </span>
                <div>
                  <div class="detail-name">{{ detail.name }}</div>
                  <div class="detail-id">企业 ID：{{ detail.id }}</div>
                </div>
              </div>
              <div class="divider" />
              <div class="detail-row">
                <span class="label">状态</span>
                <el-tag :type="detail.status === 'NORMAL' ? 'success' : 'danger'" size="small">
                  {{ detail.status === 'NORMAL' ? '正常' : '已禁用' }}
                </el-tag>
              </div>
              <div class="detail-row">
                <span class="label">创建时间</span>
                <span class="value">{{ formatTime(detail.createdAt) }}</span>
              </div>
              <el-button class="leave-btn" @click="handleLeave">离开当前企业</el-button>
            </template>
          </div>
        </el-tab-pane>

        <!-- 成员管理页签 -->
        <el-tab-pane label="成员管理" name="members">
          <MemberManagement />
        </el-tab-pane>

        <!-- 邀请管理页签 -->
        <el-tab-pane label="邀请管理" name="invitations">
          <InvitationManagement />
        </el-tab-pane>
      </el-tabs>
    </template>

    <!-- ============ 未选定企业：我的企业列表 ============ -->
    <template v-else>
      <div class="list-head">
        <h2 class="list-title">我的企业</h2>
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">
          创建企业
        </el-button>
      </div>

      <el-empty
        v-if="!loading && enterprises.length === 0"
        description="还没有企业，创建第一个吧"
      />

      <div v-else-if="!loading" class="card-grid">
        <div
          v-for="item in enterprises"
          :key="item.id"
          class="ent-card"
        >
          <div class="ent-head">
            <span class="ent-icon" :style="{ background: iconBg(item.id) }">
              {{ item.name[0] }}
            </span>
            <el-tag
              :type="item.status === 'NORMAL' ? 'success' : 'danger'"
              size="small"
            >
              {{ item.status === 'NORMAL' ? '正常' : '已禁用' }}
            </el-tag>
          </div>
          <div class="ent-name">{{ item.name }}</div>
          <div class="ent-time">创建于 {{ formatTime(item.createdAt) }}</div>
          <el-button
            type="primary"
            plain
            size="small"
            class="enter-btn"
            @click="handleEnter(item)"
          >
            进入
          </el-button>
        </div>
      </div>
    </template>

    <!-- ============ 创建企业对话框 ============ -->
    <el-dialog v-model="dialogVisible" title="创建企业" width="420px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="企业名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入企业名称" clearable maxlength="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">
          创建
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.enterprise {
  max-width: 960px;
  margin: 0 auto;
}

/* ---------- 页签样式 ---------- */
.enterprise-tabs {
  background: transparent;
}

.enterprise-tabs :deep(.el-tabs__header) {
  margin-bottom: 20px;
  background: #ffffff;
  border-radius: 12px;
  padding: 0 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.enterprise-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 0; /* 去掉默认的下划线 */
}

.enterprise-tabs :deep(.el-tabs__item) {
  font-size: 14px;
  font-weight: 500;
  height: 48px;
  line-height: 48px;
  padding: 0 20px;
  color: #606266;
  transition: color 0.2s ease;
}

.enterprise-tabs :deep(.el-tabs__item.is-active) {
  color: #409eff;
  font-weight: 600;
}

.enterprise-tabs :deep(.el-tabs__active-bar) {
  height: 3px;
  border-radius: 2px;
  background: #409eff;
}

/* ---------- 列表头部 ---------- */
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.list-title {
  font-size: 20px;
  font-weight: 600;
  color: #1f2d3d;
}

/* ---------- 企业卡片网格 ---------- */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 20px;
}

.ent-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

/* hover 轻微上浮，增强卡片层次感 */
.ent-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 24px rgba(0, 0, 0, 0.08);
}

.ent-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 14px;
}

.ent-icon {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  color: #fff;
  font-size: 20px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ent-name {
  font-size: 16px;
  font-weight: 600;
  color: #1f2d3d;
  margin-bottom: 6px;
}

.ent-time {
  font-size: 13px;
  color: #909399;
  margin-bottom: 16px;
}

.enter-btn {
  width: 100%;
}

/* ---------- 详情卡片 ---------- */
.detail-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 28px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
  min-height: 200px;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 16px;
}

.detail-icon {
  width: 60px;
  height: 60px;
  border-radius: 16px;
  color: #fff;
  font-size: 24px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.detail-name {
  font-size: 20px;
  font-weight: 600;
  color: #1f2d3d;
}

.detail-id {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}

.divider {
  height: 1px;
  background: #f0f2f5;
  margin: 20px 0;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
}

.label {
  font-size: 14px;
  color: #909399;
}

.value {
  font-size: 14px;
  color: #303133;
}

.leave-btn {
  margin-top: 20px;
  width: 100%;
}
</style>