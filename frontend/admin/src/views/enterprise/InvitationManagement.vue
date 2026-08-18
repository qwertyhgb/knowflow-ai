<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, CopyDocument } from '@element-plus/icons-vue'
import { useEnterpriseStore } from '../../stores/enterprise'
import {
  createInvitation,
  listInvitations,
  revokeInvitation,
} from '../../api/enterprise'
import type { EnterpriseInvitationVO, InvitationRole } from '../../api/types'

/**
 * 邀请管理组件。
 *
 * 展示当前企业的邀请记录，并允许创建新邀请和撤销待处理的邀请。
 * 邀请流程：创建邀请 → 获取令牌 → 分享令牌给被邀请人 → 被邀请人凭令牌加入企业。
 */

const enterpriseStore = useEnterpriseStore()

/** 当前企业 ID */
const enterpriseId = computed(() => enterpriseStore.currentEnterpriseId!)

/** 邀请列表 */
const invitations = ref<EnterpriseInvitationVO[]>([])
/** 加载中 */
const loading = ref(false)

// ---------- 创建邀请对话框 ----------
const dialogVisible = ref(false)
const creating = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  email: '',
  /** 默认角色为 MEMBER，普通成员是最常见的邀请场景 */
  role: 'MEMBER' as InvitationRole,
})

/** 表单校验规则：与后端一致 */
const rules: FormRules<typeof form> = {
  email: [
    { required: true, message: '请输入被邀请人邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
    { max: 254, message: '邮箱长度不能超过254', trigger: 'blur' },
  ],
  role: [
    { required: true, message: '请选择角色', trigger: 'change' },
  ],
}

// ---------- 创建成功令牌展示 ----------
const tokenDialogVisible = ref(false)
const createdToken = ref('')
const createdInviteeEmail = ref('')

onMounted(() => {
  loadInvitations()
})

/** 拉取邀请列表 */
async function loadInvitations() {
  loading.value = true
  try {
    invitations.value = await listInvitations(enterpriseId.value)
  } catch {
    // 错误提示已由 http.ts 响应拦截器统一处理
  } finally {
    loading.value = false
  }
}

/** 提交创建邀请 */
async function handleCreate() {
  await formRef.value?.validate(async (valid) => {
    if (!valid) return
    creating.value = true
    try {
      const result = await createInvitation(enterpriseId.value, {
        email: form.email,
        role: form.role,
      })
      ElMessage.success('邀请已创建')

      // 关闭创建对话框，重置表单
      dialogVisible.value = false
      form.email = ''
      form.role = 'MEMBER'

      // 如果创建成功响应包含 token，弹出令牌展示框
      if (result.token) {
        createdToken.value = result.token
        createdInviteeEmail.value = result.inviteeEmail
        tokenDialogVisible.value = true
      }

      // 刷新邀请列表
      await loadInvitations()
    } catch {
      // 错误提示已由 http.ts 统一处理
    } finally {
      creating.value = false
    }
  })
}

/** 复制令牌到剪贴板 */
async function copyToken() {
  try {
    await navigator.clipboard.writeText(createdToken.value)
    ElMessage.success('令牌已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

/** 撤销邀请 */
async function handleRevoke(invitation: EnterpriseInvitationVO) {
  try {
    await ElMessageBox.confirm(
      `确定要撤销发给「${invitation.inviteeEmail}」的邀请吗？撤销后该邀请将失效，被邀请人无法凭此前令牌加入。`,
      '撤销邀请',
      { confirmButtonText: '撤销', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  try {
    await revokeInvitation(enterpriseId.value, invitation.id)
    ElMessage.success('已撤销')
    await loadInvitations()
  } catch {
    // 错误提示已由 http.ts 统一处理
  }
}

/**
 * 判断邀请是否可撤销。
 * 仅 PENDING 状态且未过期的邀请可以撤销。
 * 已接受/已撤销/已过期均为终态，不可逆。
 */
function canRevoke(invitation: EnterpriseInvitationVO): boolean {
  if (invitation.status !== 'PENDING') return false
  // 检查是否过期：如果 expiresAt 已过当前时间，视为过期
  if (new Date(invitation.expiresAt) <= new Date()) return false
  return true
}

/** 角色显示文本 */
function roleLabel(role: string): string {
  return role === 'ADMIN' ? '管理员' : '成员'
}

/** 状态配置：tag 类型 + 中文文本 */
const statusMap: Record<string, { type: 'primary' | 'success' | 'info' | 'danger'; label: string }> = {
  PENDING: { type: 'primary', label: '待接受' },
  ACCEPTED: { type: 'success', label: '已接受' },
  REVOKED: { type: 'info', label: '已撤销' },
  EXPIRED: { type: 'danger', label: '已过期' },
}

/** 格式化时间 */
function formatTime(iso: string | undefined | null): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}
</script>

<template>
  <div class="invitation-management">
    <!-- 顶部操作区 -->
    <div class="section-card">
      <div class="section-header">
        <h3 class="section-title">邀请记录（{{ invitations.length }}）</h3>
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">
          邀请成员
        </el-button>
      </div>

      <!-- 邀请列表表格 -->
      <el-table
        v-loading="loading"
        :data="invitations"
        stripe
        style="width: 100%"
        :header-cell-style="{ background: '#fafafa', color: '#606266' }"
      >
        <el-table-column label="被邀请邮箱" min-width="200" prop="inviteeEmail" />

        <!-- 角色 -->
        <el-table-column label="角色" width="120">
          <template #default="{ row }: { row: EnterpriseInvitationVO }">
            <el-tag :type="row.role === 'ADMIN' ? 'primary' : 'info'" size="small" effect="light">
              {{ roleLabel(row.role) }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 状态 -->
        <el-table-column label="状态" width="120">
          <template #default="{ row }: { row: EnterpriseInvitationVO }">
            <el-tag :type="statusMap[row.status]?.type ?? 'info'" size="small">
              {{ statusMap[row.status]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 过期时间 -->
        <el-table-column label="过期时间" width="180">
          <template #default="{ row }: { row: EnterpriseInvitationVO }">
            <span class="time-text">{{ formatTime(row.expiresAt) }}</span>
          </template>
        </el-table-column>

        <!-- 创建时间 -->
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }: { row: EnterpriseInvitationVO }">
            <span class="time-text">{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>

        <!-- 操作 -->
        <el-table-column label="操作" width="120">
          <template #default="{ row }: { row: EnterpriseInvitationVO }">
            <el-button
              v-if="canRevoke(row)"
              type="warning"
              link
              size="small"
              @click="handleRevoke(row)"
            >
              撤销
            </el-button>
            <span v-else class="no-action">-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ============ 创建邀请对话框 ============ -->
    <el-dialog v-model="dialogVisible" title="邀请成员" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="被邀请人邮箱" prop="email">
          <el-input
            v-model="form.email"
            placeholder="请输入对方邮箱"
            clearable
            maxlength="254"
          />
        </el-form-item>

        <el-form-item label="授予角色" prop="role">
          <el-select v-model="form.role" placeholder="请选择角色" style="width: 100%">
            <el-option label="成员（MEMBER）" value="MEMBER" />
            <el-option label="管理员（ADMIN）" value="ADMIN" />
          </el-select>
          <!--
            【为什么没有 OWNER 选项？】
            OWNER（所有者）是创建企业时自动授予的专属角色，不可通过邀请授予。
            后端接口对 role 参数会校验，传 OWNER 会返回参数校验错误。
            这是多租户 SaaS 的常见设计：一个企业有且只有一个"创建者"作为所有者，
            所有者可以转让，但不能通过邀请批量产生。
          -->
          <p class="role-hint">所有者（OWNER）为创建企业时自动获得，不可通过邀请授予</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">
          创建邀请
        </el-button>
      </template>
    </el-dialog>

    <!-- ============ 令牌展示对话框 ============ -->
    <!--
      【为什么令牌只展示一次？】
      后端在创建邀请时返回 32 位十六进制明文令牌，但数据库中只存储 SHA-256 哈希。
      这是安全最佳实践——即使数据库泄露，攻击者也无法获取有效令牌去冒充邀请。
      代价是：系统无法找回明文令牌，所以创建时必须立即保存并分享给被邀请人。
      如果丢了，只能撤销该邀请并重新创建。
    -->
    <el-dialog v-model="tokenDialogVisible" title="邀请已创建" width="480px" :close-on-click-modal="false">
      <div class="token-body">
        <el-alert
          type="warning"
          show-icon
          :closable="false"
          title="令牌仅显示一次，请立即保存并转发给被邀请人！"
          description="关闭此对话框后，你将无法再次查看此令牌。如需重新获取，请撤销当前邀请并重新创建。"
        />
        <div class="token-info">
          <p class="token-label">被邀请人：{{ createdInviteeEmail }}</p>
        </div>
        <div class="token-box">
          <code class="token-value">{{ createdToken }}</code>
          <el-button
            type="primary"
            :icon="CopyDocument"
            size="small"
            @click="copyToken"
          >
            复制
          </el-button>
        </div>
        <p class="token-hint">
          将上方令牌通过安全渠道（邮件/即时消息等）发送给被邀请人，
          对方可在"我的邀请"页面粘贴令牌以加入企业。
        </p>
      </div>
      <template #footer>
        <el-button type="primary" @click="tokenDialogVisible = false">
          我已保存，关闭
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.invitation-management {
  max-width: 100%;
}

/* ---------- 通用卡片 ---------- */
.section-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 20px;
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

.time-text {
  font-size: 13px;
  color: #606266;
}

.no-action {
  color: #c0c4cc;
  font-size: 13px;
}

.role-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #c0c4cc;
}

/* ---------- 令牌展示 ---------- */
.token-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.token-info {
  font-size: 14px;
  color: #303133;
}

.token-label {
  font-weight: 500;
}

.token-box {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px 16px;
}

.token-value {
  flex: 1;
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 14px;
  color: #409eff;
  letter-spacing: 1px;
  word-break: break-all;
  user-select: all;
}

.token-hint {
  font-size: 13px;
  color: #909399;
  line-height: 1.6;
}
</style>