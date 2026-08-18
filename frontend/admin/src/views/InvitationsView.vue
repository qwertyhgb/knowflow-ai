<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Ticket } from '@element-plus/icons-vue'
import { listMyPendingInvitations, acceptInvitation } from '../api/invitation'
import type { EnterpriseInvitationVO } from '../api/types'

/**
 * 我的邀请页。
 *
 * 这是"被邀请人视角"的页面，不依赖企业上下文（不需要 X-Enterprise-Id 请求头），
 * 登录即可访问。用户在此页面查看自己的待处理邀请，并凭令牌加入企业。
 *
 * 【邀请闭环】
 * 1. 企业管理员在邀请管理页创建邀请 → 获得 32 位令牌
 * 2. 管理员通过任意渠道（邮件/IM）将令牌发给被邀请人
 * 3. 被邀请人登录 KnowFlow，在「我的邀请」页粘贴令牌 → 接受邀请 → 加入企业
 * 4. 刷新后可在企业列表看到该企业，成员管理页也会出现该成员
 */

/** 待处理邀请列表 */
const invitations = ref<EnterpriseInvitationVO[]>([])
/** 加载中 */
const loading = ref(false)

// ---------- 接受邀请 ----------
const token = ref('')
const accepting = ref(false)

onMounted(() => {
  loadInvitations()
})

/** 拉取待处理邀请列表 */
async function loadInvitations() {
  loading.value = true
  try {
    invitations.value = await listMyPendingInvitations()
  } catch {
    // 错误提示已由 http.ts 响应拦截器统一处理
  } finally {
    loading.value = false
  }
}

/** 接受邀请 */
async function handleAccept() {
  // 前端校验：令牌必填，长度 32-64 位（与后端一致）
  const trimmed = token.value.trim()
  if (!trimmed) {
    ElMessage.warning('请输入邀请令牌')
    return
  }
  if (trimmed.length < 32 || trimmed.length > 64) {
    ElMessage.warning('令牌长度不正确（32 位十六进制）')
    return
  }

  accepting.value = true
  try {
    await acceptInvitation({ token: trimmed })
    ElMessage.success('已成功加入企业！')
    token.value = ''
    // 接受成功后刷新列表，已接受的邀请不再显示
    await loadInvitations()
  } catch (error) {
    // 失败时展示后端返回的 message，例如"令牌已过期"、"令牌已被接受"等
    ElMessage.error((error as Error).message || '接受邀请失败')
  } finally {
    accepting.value = false
  }
}

/** 角色显示文本 */
function roleLabel(role: string): string {
  return role === 'ADMIN' ? '管理员' : '成员'
}

/** 格式化时间 */
function formatTime(iso: string | undefined): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

/** 根据角色返回不同的渐变色 */
function roleGradient(role: string): string {
  return role === 'ADMIN'
    ? 'linear-gradient(135deg, #409eff, #66b1ff)'
    : 'linear-gradient(135deg, #67c23a, #85ce61)'
}
</script>

<template>
  <div class="invitations-page">
    <h2 class="page-title">我的邀请</h2>

    <!-- ============ 接受邀请区域 ============ -->
    <div class="accept-card">
      <div class="accept-header">
        <el-icon :size="24" color="#409eff"><Ticket /></el-icon>
        <span class="accept-title">有邀请令牌？粘贴并接受</span>
      </div>
      <div class="accept-body">
        <el-input
          v-model="token"
          placeholder="粘贴 32 位邀请令牌"
          clearable
          class="token-input"
          maxlength="64"
        />
        <el-button
          type="primary"
          :icon="Check"
          :loading="accepting"
          @click="handleAccept"
          class="accept-btn"
        >
          {{ accepting ? '接受中…' : '接受邀请' }}
        </el-button>
      </div>
      <p class="accept-hint">
        将管理员分享给你的邀请令牌粘贴到上方输入框，即可加入对应企业。
      </p>
    </div>

    <!-- ============ 待处理邀请列表 ============ -->
    <div class="list-card">
      <h3 class="list-title">待处理邀请（{{ invitations.length }}）</h3>

      <el-empty
        v-if="!loading && invitations.length === 0"
        description="暂无待处理的邀请"
      />

      <div v-else-if="!loading" class="invite-grid">
        <div
          v-for="item in invitations"
          :key="item.id"
          class="invite-card"
        >
          <div class="invite-head">
            <span class="invite-icon" :style="{ background: roleGradient(item.role) }">
              {{ item.enterpriseName?.[0] ?? '?' }}
            </span>
            <div class="invite-meta">
              <div class="invite-enterprise">{{ item.enterpriseName ?? '未知企业' }}</div>
              <el-tag
                :type="item.role === 'ADMIN' ? 'primary' : 'success'"
                size="small"
                effect="light"
              >
                {{ roleLabel(item.role) }}
              </el-tag>
            </div>
          </div>
          <div class="invite-body">
            <div class="invite-row">
              <span class="label">过期时间</span>
              <span class="value">{{ formatTime(item.expiresAt) }}</span>
            </div>
            <div class="invite-row">
              <span class="label">邀请时间</span>
              <span class="value">{{ formatTime(item.createdAt) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.invitations-page {
  max-width: 720px;
  margin: 0 auto;
}

.page-title {
  font-size: 22px;
  font-weight: 600;
  color: #1f2d3d;
  margin-bottom: 20px;
}

/* ---------- 接受邀请卡片 ---------- */
.accept-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
  margin-bottom: 20px;
}

.accept-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.accept-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2d3d;
}

.accept-body {
  display: flex;
  gap: 12px;
}

.token-input {
  flex: 1;
}

.accept-btn {
  flex-shrink: 0;
}

.accept-hint {
  margin-top: 12px;
  font-size: 13px;
  color: #909399;
}

/* ---------- 邀请列表 ---------- */
.list-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
}

.list-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2d3d;
  margin-bottom: 16px;
}

.invite-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.invite-card {
  background: #fafafa;
  border-radius: 12px;
  padding: 16px;
  border: 1px solid #f0f0f0;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.invite-card:hover {
  border-color: #d9ecff;
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.08);
}

.invite-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.invite-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.invite-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.invite-enterprise {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.invite-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.invite-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}

.invite-row .label {
  color: #909399;
}

.invite-row .value {
  color: #606266;
}
</style>