<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '../../stores/user'
import { useEnterpriseStore } from '../../stores/enterprise'
import {
  getMembers,
  removeMember,
  updateMemberStatus,
  leaveEnterprise,
} from '../../api/enterprise'
import type { EnterpriseMemberVO } from '../../api/types'

/**
 * 成员管理组件。
 *
 * 展示当前企业的成员列表，并允许拥有权限的成员（OWNER/ADMIN）管理成员。
 * 注意：前端的按角色显隐只是体验层，真正的安全边界在后端——权限不足时接口返回 403。
 */

const userStore = useUserStore()
const enterpriseStore = useEnterpriseStore()

/** 当前企业 ID */
const enterpriseId = computed(() => enterpriseStore.currentEnterpriseId!)

/** 成员列表 */
const members = ref<EnterpriseMemberVO[]>([])
/** 加载中 */
const loading = ref(false)

/** 当前登录用户的 userId（从 user store 获取） */
const currentUserId = computed(() => userStore.user?.id)

/** 当前登录用户在当前企业的角色 */
const currentUserRole = computed(() => {
  if (currentUserId.value === undefined) return null
  return members.value.find((m) => m.userId === currentUserId.value)?.roleCode ?? null
})

/**
 * 当前用户是否有管理权限（OWNER 或 ADMIN）。
 * 用于控制操作列的显隐。
 *
 * 【为什么前端按角色显隐只是体验层？】
 * 后端 403 才是真正的安全边界。即使前端隐藏了按钮，恶意用户仍可通过
 * 浏览器 DevTools 手动调用 API。后端会校验 token 和权限码，因此
 * 前端显隐只是"让普通用户看不到操作按钮，体验更顺畅"。
 */
const canManage = computed(() => {
  const role = currentUserRole.value
  return role === 'OWNER' || role === 'ADMIN'
})

/** 退出企业 loading */
const leaving = ref(false)

onMounted(() => {
  loadMembers()
})

/** 拉取成员列表 */
async function loadMembers() {
  loading.value = true
  try {
    members.value = await getMembers(enterpriseId.value)
  } catch {
    // 错误提示已由 http.ts 响应拦截器统一处理
  } finally {
    loading.value = false
  }
}

/**
 * 判断某行是否应该显示操作按钮。
 * 规则：
 * 1. 当前用户无管理权限 → 不显示
 * 2. OWNER 行 → 不显示任何操作（后端禁止操作 OWNER）
 * 3. 当前用户自己的行 → 不显示操作（自己不能移除/禁用自己）
 * 4. 其他成员 → 显示
 */
function shouldShowActions(member: EnterpriseMemberVO): boolean {
  if (!canManage.value) return false
  if (member.roleCode === 'OWNER') return false
  if (member.userId === currentUserId.value) return false
  return true
}

/** 禁用成员 */
async function handleDisable(member: EnterpriseMemberVO) {
  try {
    await ElMessageBox.confirm(
      `确定要禁用「${member.nickname || member.email}」吗？禁用后该成员将无法访问当前企业。`,
      '禁用成员',
      { confirmButtonText: '禁用', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return // 用户取消
  }

  try {
    await updateMemberStatus(enterpriseId.value, member.userId, { status: 'DISABLED' })
    ElMessage.success('已禁用')
    await loadMembers()
  } catch {
    // 错误提示已由 http.ts 统一处理
  }
}

/** 恢复成员（从禁用状态恢复为正常） */
async function handleEnable(member: EnterpriseMemberVO) {
  try {
    await ElMessageBox.confirm(
      `确定要恢复「${member.nickname || member.email}」吗？恢复后该成员可正常访问当前企业。`,
      '恢复成员',
      { confirmButtonText: '恢复', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }

  try {
    await updateMemberStatus(enterpriseId.value, member.userId, { status: 'NORMAL' })
    ElMessage.success('已恢复')
    await loadMembers()
  } catch {
    // 错误提示已由 http.ts 统一处理
  }
}

/** 移除成员 */
async function handleRemove(member: EnterpriseMemberVO) {
  try {
    await ElMessageBox.confirm(
      `确定要将「${member.nickname || member.email}」移出企业吗？此操作不可撤销。`,
      '移除成员',
      { confirmButtonText: '移除', cancelButtonText: '取消', type: 'error' },
    )
  } catch {
    return
  }

  try {
    await removeMember(enterpriseId.value, member.userId)
    ElMessage.success('已移除')
    await loadMembers()
  } catch {
    // 错误提示已由 http.ts 统一处理
  }
}

/** 退出当前企业（主动退出，区别于被移除） */
async function handleLeave() {
  try {
    await ElMessageBox.confirm(
      '确定要退出当前企业吗？退出后如需重新加入，需要新的邀请。',
      '退出企业',
      { confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  leaving.value = true
  try {
    await leaveEnterprise(enterpriseId.value)
    ElMessage.success('已退出当前企业')
    enterpriseStore.clearCurrentEnterpriseId()
  } catch {
    // 错误提示已由 http.ts 统一处理
  } finally {
    leaving.value = false
  }
}

/** 角色显示文本 */
function roleLabel(roleCode: string | null): string {
  const map: Record<string, string> = { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' }
  return map[roleCode ?? ''] || '-'
}

/** 角色 tag 类型 */
function roleTagType(roleCode: string | null): 'warning' | 'primary' | 'info' {
  const map: Record<string, 'warning' | 'primary' | 'info'> = {
    OWNER: 'warning',
    ADMIN: 'primary',
    MEMBER: 'info',
  }
  return map[roleCode ?? ''] || 'info'
}

/** 状态 tag 类型 */
function statusTagType(status: string): 'success' | 'danger' {
  return status === 'NORMAL' ? 'success' : 'danger'
}

/** 状态显示文本 */
function statusLabel(status: string): string {
  return status === 'NORMAL' ? '正常' : '已禁用'
}

/** 格式化时间：ISO UTC 字符串转本地时间 */
function formatTime(iso: string | undefined): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

/** 渐变色数组，用于成员头像 */
const gradients = [
  'linear-gradient(135deg, #409eff, #66b1ff)',
  'linear-gradient(135deg, #67c23a, #85ce61)',
  'linear-gradient(135deg, #e6a23c, #ebb563)',
  'linear-gradient(135deg, #f56c6c, #f78989)',
  'linear-gradient(135deg, #909399, #a8abb2)',
  'linear-gradient(135deg, #b37feb, #d3adf7)',
  'linear-gradient(135deg, #ff85c0, #ffadd2)',
  'linear-gradient(135deg, #5cdbd3, #87e8de)',
]
function avatarBg(userId: number): string {
  return gradients[userId % gradients.length]
}
</script>

<template>
  <div class="member-management">
    <!-- 成员列表卡片 -->
    <div class="section-card">
      <div class="section-header">
        <h3 class="section-title">成员列表（{{ members.length }}）</h3>
      </div>

      <el-table
        v-loading="loading"
        :data="members"
        stripe
        style="width: 100%"
        :header-cell-style="{ background: '#fafafa', color: '#606266' }"
      >
        <!-- 头像 + 昵称 -->
        <el-table-column label="成员" min-width="200">
          <template #default="{ row }: { row: EnterpriseMemberVO }">
            <div class="member-cell">
              <span
                class="member-avatar"
                :style="{ background: avatarBg(row.userId) }"
              >
                {{ row.nickname?.[0] ?? '?' }}
              </span>
              <div class="member-info">
                <span class="member-name">
                  {{ row.nickname ?? '未设置昵称' }}
                  <!-- 当前登录用户自己，显示「我」标签 -->
                  <el-tag v-if="row.userId === currentUserId" size="small" type="primary" effect="plain" class="me-tag">我</el-tag>
                </span>
                <span class="member-email">{{ row.email ?? '-' }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <!-- 角色 -->
        <el-table-column label="角色" width="120">
          <template #default="{ row }: { row: EnterpriseMemberVO }">
            <el-tag :type="roleTagType(row.roleCode)" size="small" effect="light">
              {{ roleLabel(row.roleCode) }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 状态 -->
        <el-table-column label="状态" width="100">
          <template #default="{ row }: { row: EnterpriseMemberVO }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 加入时间 -->
        <el-table-column label="加入时间" width="180">
          <template #default="{ row }: { row: EnterpriseMemberVO }">
            <span class="time-text">{{ formatTime(row.joinedAt) }}</span>
          </template>
        </el-table-column>

        <!-- 操作列 -->
        <el-table-column label="操作" width="200" v-if="canManage">
          <template #default="{ row }: { row: EnterpriseMemberVO }">
            <div v-if="shouldShowActions(row)" class="action-btns">
              <!-- 禁用/恢复切换 -->
              <el-button
                v-if="row.status === 'NORMAL'"
                type="warning"
                link
                size="small"
                @click="handleDisable(row)"
              >
                禁用
              </el-button>
              <el-button
                v-else
                type="success"
                link
                size="small"
                @click="handleEnable(row)"
              >
                恢复
              </el-button>
              <!-- 移除 -->
              <el-button
                type="danger"
                link
                size="small"
                @click="handleRemove(row)"
              >
                移除
              </el-button>
            </div>
            <span v-else class="no-action">-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 退出当前企业按钮（普通成员可用此方式离开企业） -->
    <div class="leave-section">
      <el-button
        type="danger"
        plain
        :loading="leaving"
        @click="handleLeave"
        class="leave-btn"
      >
        退出当前企业
      </el-button>
      <p class="leave-hint">
        【退出与移除的区别】退出是主动操作，由成员自己发起；移除是被管理操作，由管理员执行。
      </p>
    </div>
  </div>
</template>

<style scoped>
.member-management {
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

/* ---------- 成员单元格 ---------- */
.member-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.member-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.member-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.member-name {
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

.member-email {
  font-size: 12px;
  color: #909399;
}

.time-text {
  font-size: 13px;
  color: #606266;
}

.action-btns {
  display: flex;
  gap: 4px;
}

.no-action {
  color: #c0c4cc;
  font-size: 13px;
}

/* ---------- 退出区 ---------- */
.leave-section {
  margin-top: 20px;
  text-align: center;
}

.leave-btn {
  width: 100%;
  max-width: 300px;
}

.leave-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #c0c4cc;
}
</style>