<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  ArrowLeft,
  Edit,
  Delete,
  Plus,
} from '@element-plus/icons-vue'
import { useEnterpriseStore } from '../stores/enterprise'
import { useUserStore } from '../stores/user'
import { getMembers } from '../api/enterprise'
import {
  getKnowledgeBaseDetail,
  getKnowledgeBaseMembers,
  addKnowledgeBaseMember,
  updateKnowledgeBaseMemberRole,
  removeKnowledgeBaseMember,
  updateKnowledgeBase,
  deleteKnowledgeBase,
  updateKnowledgeBaseStatus,
} from '../api/knowledgeBase'
import type {
  KnowledgeBaseVO,
  KnowledgeBaseMemberVO,
  KnowledgeBaseMemberRole,
  KnowledgeBaseAccessMode,
  EnterpriseMemberVO,
} from '../api/types'
import DocumentManagement from './knowledgeBase/DocumentManagement.vue'

/**
 * 知识库详情页。
 *
 * 路由参数 knowledgeBaseId，展示当前选定企业的某个知识库的详细信息。
 * 知识库从属于当前企业，不改变企业上下文（X-Enterprise-Id 不变）。
 */

const route = useRoute()
const router = useRouter()
const enterpriseStore = useEnterpriseStore()
const userStore = useUserStore()

const knowledgeBaseId = computed(() => Number(route.params.knowledgeBaseId))
const enterpriseId = computed(() => enterpriseStore.currentEnterpriseId!)

/** 知识库详情 */
const detail = ref<KnowledgeBaseVO | null>(null)
/** 加载中 */
const loading = ref(false)

/** 当前激活的页签 */
const activeTab = ref('info')

/**
 * 当前用户是否有管理权限。
 * 知识库 ADMIN 成员或企业 OWNER/ADMIN 可执行管理操作。
 * 注意：前端按角色显隐只是体验层，后端才是安全边界。
 */
const currentUserIsAdmin = computed(() => {
  const role = detail.value?.myRole
  return role === 'ADMIN'
})

onMounted(() => {
  loadDetail()
})

/** 拉取知识库详情 */
async function loadDetail() {
  loading.value = true
  try {
    detail.value = await getKnowledgeBaseDetail(
      enterpriseId.value,
      knowledgeBaseId.value,
    )
  } catch {
    // 如果拉取失败（如权限不足 403），回退到列表页
    ElMessage.error('无法获取知识库详情')
    router.push('/knowledge-bases')
  } finally {
    loading.value = false
  }
}

/** 返回知识库列表 */
function goBack() {
  router.push('/knowledge-bases')
}

// ============================================================
// 基本信息页签
// ============================================================

// ---------- 编辑知识库对话框 ----------
const editDialogVisible = ref(false)
const editing = ref(false)
const editFormRef = ref<FormInstance>()
const editForm = reactive({
  name: '',
  description: '',
  accessMode: 'PRIVATE' as KnowledgeBaseAccessMode,
})

const editRules: FormRules<typeof editForm> = {
  name: [
    { required: true, message: '请输入知识库名称', trigger: 'blur' },
    { max: 100, message: '名称长度不能超过100', trigger: 'blur' },
  ],
  description: [
    { max: 500, message: '描述长度不能超过500', trigger: 'blur' },
  ],
  accessMode: [
    { required: true, message: '请选择访问模式', trigger: 'change' },
  ],
}

/** 打开编辑对话框，用当前值填充表单 */
function openEditDialog() {
  if (!detail.value) return
  editForm.name = detail.value.name
  editForm.description = detail.value.description ?? ''
  editForm.accessMode = detail.value.accessMode
  editDialogVisible.value = true
}

/** 提交编辑 */
async function handleEdit() {
  await editFormRef.value?.validate(async (valid) => {
    if (!valid) return
    editing.value = true
    try {
      detail.value = await updateKnowledgeBase(
        enterpriseId.value,
        knowledgeBaseId.value,
        {
          name: editForm.name,
          description: editForm.description || undefined,
          accessMode: editForm.accessMode,
        },
      )
      ElMessage.success('已更新')
      editDialogVisible.value = false
    } catch {
      // 错误提示已由 http.ts 统一处理
    } finally {
      editing.value = false
    }
  })
}

/** 禁用/启用知识库 */
async function handleToggleStatus() {
  if (!detail.value) return
  const isDisabled = detail.value.status === 'DISABLED'
  const action = isDisabled ? '启用' : '禁用'

  try {
    await ElMessageBox.confirm(
      `确定要${action}知识库「${detail.value.name}」吗？${isDisabled ? '' : '禁用后成员将无法访问该知识库。'}`,
      `${action}知识库`,
      { confirmButtonText: action, cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  try {
    detail.value = await updateKnowledgeBaseStatus(
      enterpriseId.value,
      knowledgeBaseId.value,
      { status: isDisabled ? 'NORMAL' : 'DISABLED' },
    )
    ElMessage.success(`已${action}`)
  } catch {
    // 错误提示已由 http.ts 统一处理
  }
}

/** 删除知识库 */
async function handleDelete() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(
      `确定要删除知识库「${detail.value.name}」吗？删除后知识库及其全部成员记录将不可恢复。`,
      '删除知识库',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'error' },
    )
  } catch {
    return
  }

  try {
    await deleteKnowledgeBase(enterpriseId.value, knowledgeBaseId.value)
    ElMessage.success('已删除')
    router.push('/knowledge-bases')
  } catch {
    // 错误提示已由 http.ts 统一处理
  }
}

// ============================================================
// 成员管理页签
// ============================================================

const kbMembers = ref<KnowledgeBaseMemberVO[]>([])
const kbMembersLoading = ref(false)
const enterpriseMembers = ref<EnterpriseMemberVO[]>([])

/** 当前登录用户 ID */
const currentUserId = computed(() => userStore.user?.id)

/**
 * userId → 昵称映射。
 * 知识库成员接口不返回用户昵称/邮箱，需要从企业成员列表映射。
 *
 * 【为什么知识库成员接口不返回用户信息？】
 * 这是 VO 最小化设计——KnowledgeBaseMemberVO 只包含知识库成员关系本身的数据，
 * 不冗余存储用户信息。用户信息已有企业成员列表接口提供，前端做一次映射即可。
 * 这避免了跨服务/跨表的数据冗余，也减少了当用户信息变化时的同步问题。
 */
const userMap = computed(() => {
  const map = new Map<number, EnterpriseMemberVO>()
  for (const m of enterpriseMembers.value) {
    map.set(m.userId, m)
  }
  return map
})

/** 知识库成员带用户信息的合并列表 */
const mergedMembers = computed(() => {
  return kbMembers.value.map((kbm) => {
    const userInfo = userMap.value.get(kbm.userId)
    return {
      ...kbm,
      nickname: userInfo?.nickname ?? null,
      email: userInfo?.email ?? null,
      /** 如果能映射到企业成员但用户信息缺失，显示 userId */
      displayName: userInfo?.nickname ?? userInfo?.email ?? `用户(${kbm.userId})`,
      displayEmail: userInfo?.email ?? `ID: ${kbm.userId}`,
    }
  })
})

/** 加载知识库成员和企业成员列表（并行拉取） */
async function loadMembers() {
  kbMembersLoading.value = true
  try {
    // 并行拉取两个列表，减少等待时间
    const [kbMembersRes, enterpriseMembersRes] = await Promise.all([
      getKnowledgeBaseMembers(enterpriseId.value, knowledgeBaseId.value),
      getMembers(enterpriseId.value),
    ])
    kbMembers.value = kbMembersRes
    enterpriseMembers.value = enterpriseMembersRes
  } catch {
    // 错误提示已由 http.ts 统一处理
  } finally {
    kbMembersLoading.value = false
  }
}

// ---------- 添加成员对话框 ----------
const addMemberDialogVisible = ref(false)
const addingMember = ref(false)
const addMemberForm = reactive({
  userId: null as number | null,
  memberRole: 'VIEWER' as KnowledgeBaseMemberRole,
})

/** 可添加的企业成员：排除已在知识库中的 */
const availableMembers = computed(() => {
  const kbUserIds = new Set(kbMembers.value.map((m) => m.userId))
  return enterpriseMembers.value.filter((m) => !kbUserIds.has(m.userId))
})

/** 提交添加成员 */
async function handleAddMember() {
  if (addMemberForm.userId === null) {
    ElMessage.warning('请选择用户')
    return
  }
  addingMember.value = true
  try {
    await addKnowledgeBaseMember(enterpriseId.value, knowledgeBaseId.value, {
      userId: addMemberForm.userId,
      memberRole: addMemberForm.memberRole,
    })
    ElMessage.success('成员已添加')
    addMemberDialogVisible.value = false
    addMemberForm.userId = null
    addMemberForm.memberRole = 'VIEWER'
    await loadMembers()
  } catch {
    // 错误提示已由 http.ts 统一处理
  } finally {
    addingMember.value = false
  }
}

/** 行内改角色 */
async function handleChangeRole(member: KnowledgeBaseMemberVO, newRole: KnowledgeBaseMemberRole) {
  try {
    await updateKnowledgeBaseMemberRole(
      enterpriseId.value,
      knowledgeBaseId.value,
      member.userId,
      { memberRole: newRole },
    )
    ElMessage.success('角色已更新')
    await loadMembers()
  } catch {
    // 错误提示已由 http.ts 统一处理
  }
}

/** 移除成员 */
async function handleRemoveMember(member: KnowledgeBaseMemberVO) {
  const displayName = userMap.value.get(member.userId)?.nickname
    ?? userMap.value.get(member.userId)?.email
    ?? `用户(${member.userId})`

  try {
    await ElMessageBox.confirm(
      `确定要将「${displayName}」移出知识库吗？`,
      '移除成员',
      { confirmButtonText: '移除', cancelButtonText: '取消', type: 'error' },
    )
  } catch {
    return
  }

  try {
    await removeKnowledgeBaseMember(
      enterpriseId.value,
      knowledgeBaseId.value,
      member.userId,
    )
    ElMessage.success('已移除')
    await loadMembers()
  } catch {
    // 错误提示已由 http.ts 统一处理
  }
}

/** 角色 tag 配置 */
const roleTagConfig: Record<string, { type: 'primary' | 'success' | 'info'; label: string }> = {
  ADMIN: { type: 'primary', label: '管理' },
  EDITOR: { type: 'success', label: '可编辑' },
  VIEWER: { type: 'info', label: '只读' },
}

// 页签切换时加载成员列表
watch(activeTab, (tab) => {
  if (tab === 'members' && kbMembers.value.length === 0) {
    loadMembers()
  }
})

/** 格式化时间 */
function formatTime(iso: string | undefined | null): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}
</script>

<template>
  <div class="kb-detail" v-loading="loading">
    <template v-if="detail">
      <!-- 顶部返回栏 -->
      <div class="top-bar">
        <el-button text :icon="ArrowLeft" @click="goBack">
          返回知识库列表
        </el-button>
        <h2 class="kb-title">{{ detail.name }}</h2>
      </div>

      <!-- 页签 -->
      <el-tabs v-model="activeTab" class="detail-tabs">
        <!-- ========== 基本信息页签 ========== -->
        <el-tab-pane label="基本信息" name="info">
          <div class="section-card">
            <!-- 描述 -->
            <div class="info-section">
              <h4 class="info-label">描述</h4>
              <p class="info-value desc-text">{{ detail.description || '暂无描述' }}</p>
            </div>

            <div class="divider" />

            <!-- 访问模式 -->
            <div class="info-row">
              <span class="info-label">访问模式</span>
              <el-tag
                :type="detail.accessMode === 'PUBLIC' ? 'success' : 'info'"
                size="small"
              >
                {{ detail.accessMode === 'PUBLIC' ? '公开' : '私有' }}
              </el-tag>
            </div>

            <!-- 状态 -->
            <div class="info-row">
              <span class="info-label">状态</span>
              <el-tag
                :type="detail.status === 'NORMAL' ? 'success' : 'danger'"
                size="small"
              >
                {{ detail.status === 'NORMAL' ? '正常' : '已禁用' }}
              </el-tag>
            </div>

            <!-- 创建时间 -->
            <div class="info-row">
              <span class="info-label">创建时间</span>
              <span class="info-value">{{ formatTime(detail.createdAt) }}</span>
            </div>

            <!-- 我的角色 -->
            <div class="info-row">
              <span class="info-label">我的角色</span>
              <el-tag
                v-if="detail.myRole"
                :type="roleTagConfig[detail.myRole]?.type ?? 'info'"
                size="small"
              >
                {{ roleTagConfig[detail.myRole]?.label ?? detail.myRole }}
              </el-tag>
              <span v-else class="info-value">非成员</span>
            </div>

            <!-- 管理员操作按钮组 -->
            <template v-if="currentUserIsAdmin">
              <div class="divider" />
              <div class="action-group">
                <el-button type="primary" :icon="Edit" @click="openEditDialog">
                  编辑
                </el-button>
                <el-button
                  :type="detail.status === 'DISABLED' ? 'success' : 'warning'"
                  plain
                  @click="handleToggleStatus"
                >
                  {{ detail.status === 'DISABLED' ? '启用' : '禁用' }}
                </el-button>
                <el-button type="danger" plain :icon="Delete" @click="handleDelete">
                  删除
                </el-button>
              </div>
            </template>
          </div>
        </el-tab-pane>

        <!-- ========== 成员管理页签 ========== -->
        <el-tab-pane label="成员管理" name="members">
          <div class="section-card">
            <div class="section-header">
              <h3 class="section-title">知识库成员（{{ kbMembers.length }}）</h3>
              <el-button
                v-if="currentUserIsAdmin"
                type="primary"
                :icon="Plus"
                size="small"
                @click="addMemberDialogVisible = true"
              >
                添加成员
              </el-button>
            </div>

            <el-table
              v-loading="kbMembersLoading"
              :data="mergedMembers"
              stripe
              style="width: 100%"
              :header-cell-style="{ background: '#fafafa', color: '#606266' }"
            >
              <el-table-column label="用户" min-width="200">
                <template #default="{ row }">
                  <div class="user-cell">
                    <span class="user-name">
                      {{ row.displayName }}
                      <el-tag v-if="row.userId === currentUserId" size="small" type="primary" effect="plain" class="me-tag">我</el-tag>
                    </span>
                    <span class="user-email">{{ row.displayEmail }}</span>
                  </div>
                </template>
              </el-table-column>

              <el-table-column label="角色" width="160">
                <template #default="{ row }">
                  <!-- 管理员可以行内改角色（但不能改自己） -->
                  <el-select
                    v-if="currentUserIsAdmin && row.userId !== currentUserId"
                    :model-value="row.memberRole"
                    size="small"
                    @change="(val: KnowledgeBaseMemberRole) => handleChangeRole(row, val)"
                  >
                    <el-option label="管理" value="ADMIN" />
                    <el-option label="可编辑" value="EDITOR" />
                    <el-option label="只读" value="VIEWER" />
                  </el-select>
                  <el-tag
                    v-else
                    :type="roleTagConfig[row.memberRole]?.type ?? 'info'"
                    size="small"
                  >
                    {{ roleTagConfig[row.memberRole]?.label ?? row.memberRole }}
                  </el-tag>
                </template>
              </el-table-column>

              <el-table-column label="加入时间" width="180">
                <template #default="{ row }">
                  <span class="time-text">{{ formatTime(row.createdAt) }}</span>
                </template>
              </el-table-column>

              <el-table-column label="操作" width="100" v-if="currentUserIsAdmin">
                <template #default="{ row }">
                  <el-button
                    v-if="row.userId !== currentUserId"
                    type="danger"
                    link
                    size="small"
                    @click="handleRemoveMember(row)"
                  >
                    移除
                  </el-button>
                  <span v-else class="no-action">-</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <!-- ========== 文档页签 ========== -->
        <el-tab-pane label="文档" name="documents">
          <!--
            my-role 透传当前用户在知识库的角色（detail 为 null 时整个模板分支不渲染，
            所以此处 detail.myRole 一定可安全读取；非成员时后端返回 null，
            子组件按无编辑权限处理）
          -->
          <DocumentManagement
            :knowledge-base-id="knowledgeBaseId"
            :enterprise-id="enterpriseId"
            :my-role="detail.myRole"
          />
        </el-tab-pane>
      </el-tabs>
    </template>

    <!-- ============ 编辑知识库对话框 ============ -->
    <el-dialog v-model="editDialogVisible" title="编辑知识库" width="480px">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-position="top">
        <el-form-item label="名称" prop="name">
          <el-input v-model="editForm.name" placeholder="请输入知识库名称" maxlength="100" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="3"
            placeholder="请输入知识库描述"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="访问模式" prop="accessMode">
          <el-radio-group v-model="editForm.accessMode">
            <el-radio value="PRIVATE">
              <div class="radio-option">
                <span class="radio-label">私有</span>
                <span class="radio-desc">仅知识库成员可见</span>
              </div>
            </el-radio>
            <el-radio value="PUBLIC">
              <div class="radio-option">
                <span class="radio-label">公开</span>
                <span class="radio-desc">企业内所有成员可见</span>
              </div>
            </el-radio>
          </el-radio-group>
          <!--
            【为什么更新是全量语义？】
            后端 PUT 接口要求全量字段。即使只改了名称，也必须传 description 和 accessMode，
            不传的字段会被后端置为 null 或默认值。所以编辑对话框总是展示当前值让用户确认。
            这与 PATCH（部分更新）不同——PUT 是全量替换，PATCH 是部分修改。
          -->
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="handleEdit">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- ============ 添加成员对话框 ============ -->
    <el-dialog v-model="addMemberDialogVisible" title="添加成员" width="480px">
      <el-form label-position="top">
        <el-form-item label="选择用户">
          <el-select
            v-model="addMemberForm.userId"
            placeholder="请选择企业成员"
            style="width: 100%"
            filterable
          >
            <!--
              【为什么添加成员只能选企业成员？】
              知识库从属于企业，知识库成员必须是企业成员。
              后端会校验 userId 是否属于当前企业，如果不是企业成员则返回 400。
              这是多租户 SaaS 的基本隔离规则：一个企业的知识库只能由该企业成员操作。
            -->
            <el-option
              v-for="m in availableMembers"
              :key="m.userId"
              :value="m.userId"
              :label="`${m.nickname ?? '未设置昵称'}（${m.email ?? 'ID: ' + m.userId}）`"
            />
            <template #empty>
              <el-empty description="没有可添加的企业成员" :image-size="60" />
            </template>
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="addMemberForm.memberRole"
            style="width: 100%"
          >
            <el-option label="只读（VIEWER）" value="VIEWER" />
            <el-option label="可编辑（EDITOR）" value="EDITOR" />
            <el-option label="管理（ADMIN）" value="ADMIN" />
          </el-select>
          <!--
            【为什么默认角色是 VIEWER？】
            VIEWER 是最低权限角色，遵循最小权限原则——默认只给查看权限，
            如果用户确实需要编辑或管理权限，再显式升级。
            这在安全设计中称为"默认拒绝"（default deny）的变体。
          -->
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addMemberDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="addingMember" @click="handleAddMember">
          添加
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kb-detail {
  max-width: 960px;
  margin: 0 auto;
}

/* ---------- 顶部返回栏 ---------- */
.top-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.kb-title {
  font-size: 20px;
  font-weight: 600;
  color: #1f2d3d;
}

/* ---------- 页签 ---------- */
.detail-tabs {
  background: transparent;
}

.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 20px;
  background: #ffffff;
  border-radius: 12px;
  padding: 0 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.detail-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 0;
}

.detail-tabs :deep(.el-tabs__item) {
  font-size: 14px;
  font-weight: 500;
  height: 48px;
  line-height: 48px;
  padding: 0 20px;
  color: #606266;
  transition: color 0.2s ease;
}

.detail-tabs :deep(.el-tabs__item.is-active) {
  color: #409eff;
  font-weight: 600;
}

.detail-tabs :deep(.el-tabs__active-bar) {
  height: 3px;
  border-radius: 2px;
  background: #409eff;
}

/* ---------- 通用卡片 ---------- */
.section-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2d3d;
}

/* ---------- 基本信息 ---------- */
.info-section {
  margin-bottom: 4px;
}

.info-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 6px;
  font-weight: 400;
}

.info-value {
  font-size: 14px;
  color: #303133;
}

.desc-text {
  line-height: 1.6;
}

.divider {
  height: 1px;
  background: #f0f2f5;
  margin: 16px 0;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
}

.action-group {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

/* ---------- 成员表格 ---------- */
.user-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.user-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 6px;
}

.me-tag {
  font-size: 11px;
  height: 20px;
  line-height: 20px;
  padding: 0 6px;
}

.user-email {
  font-size: 12px;
  color: #909399;
}

.time-text {
  font-size: 13px;
  color: #606266;
}

.no-action {
  color: #c0c4cc;
  font-size: 13px;
}

/* ---------- 占位卡片 ---------- */
.placeholder-card {
  text-align: center;
  padding: 60px 40px;
}

.placeholder-title {
  font-size: 18px;
  font-weight: 600;
  color: #606266;
  margin: 16px 0 8px;
}

.placeholder-desc {
  font-size: 14px;
  color: #909399;
}

/* ---------- 对话框 radio 选项 ---------- */
.radio-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.radio-label {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.radio-desc {
  font-size: 12px;
  color: #909399;
}
</style>