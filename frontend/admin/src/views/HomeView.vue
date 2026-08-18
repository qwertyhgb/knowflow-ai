<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getMe } from '../api/user'
import { useUserStore } from '../stores/user'

/**
 * 首页。
 *
 * 进入时调用 getMe() 加载当前登录用户信息并展示。
 * 因为 user 只存在 Pinia 内存中（刷新后为空），所以每次进入首页都重新拉取，
 * 保证展示的是最新、可信的数据。
 */

const userStore = useUserStore()

/** 加载状态：true 时显示骨架屏，避免空白页闪烁 */
const loading = ref(true)

onMounted(async () => {
  try {
    const user = await getMe()
    userStore.setUser(user)
  } catch {
    // 拉取失败（如 token 已失效返回 401）。http.ts 已对非登录请求的 401
    // 统一弹出"登录已过期"，这里只做兜底的页面级提示。
    ElMessage.error('获取用户信息失败，请重新登录')
  } finally {
    loading.value = false
  }
})

/** 昵称首字母：用于头像圆形图标 */
const initial = computed(() => userStore.user?.nickname?.[0] ?? '末')

/** 账号创建时间：ISO UTC 字符串转本地可读时间，仅在展示层做时区转换 */
const createdAtText = computed(() => {
  if (!userStore.user?.createdAt) return '-'
  return new Date(userStore.user.createdAt).toLocaleString('zh-CN')
})

/** 欢迎语：根据昵称个性化 */
const welcome = computed(() => {
  return userStore.user?.nickname
    ? `${userStore.user.nickname}，欢迎回来！`
    : '欢迎回来！'
})
</script>

<template>
  <div class="home">
    <!-- 欢迎横幅，卡片风格 -->
    <div class="welcome-card">
      <div>
        <h1 class="welcome-title">{{ welcome }}</h1>
        <p class="welcome-sub">高效管理企业的知识资产，让 AI 为你所用。</p>
      </div>
    </div>

    <!-- 用户信息卡片 -->
    <div class="info-card">
      <el-skeleton :loading="loading" animated :rows="3" class="skeleton">
        <template #template>
          <div class="skeleton-box">
            <el-skeleton-item variant="circle" class="sk-avatar" />
            <div class="sk-lines">
              <el-skeleton-item variant="h3" class="sk-line" />
              <el-skeleton-item variant="text" class="sk-line" />
              <el-skeleton-item variant="text" class="sk-line" />
            </div>
          </div>
        </template>

        <template #default>
          <div class="user">
            <div class="user-avatar">{{ initial }}</div>
            <div class="user-meta">
              <div class="user-name">{{ userStore.user?.nickname }}</div>
              <div class="user-email">{{ userStore.user?.email }}</div>
            </div>
          </div>
          <div class="divider" />
          <div class="detail-row">
            <span class="label">账号状态</span>
            <el-tag :type="userStore.user?.status === 'NORMAL' ? 'success' : 'danger'" size="small">
              {{ userStore.user?.status === 'NORMAL' ? '正常' : '已禁用' }}
            </el-tag>
          </div>
          <div class="detail-row">
            <span class="label">注册时间</span>
            <span class="value">{{ createdAtText }}</span>
          </div>
        </template>
      </el-skeleton>
    </div>
  </div>
</template>

<style scoped>
.home {
  max-width: 720px;
  margin: 0 auto;
}

.welcome-card {
  background: linear-gradient(120deg, #409eff 0%, #79bbff 100%);
  border-radius: 16px;
  padding: 28px 32px;
  color: #fff;
  box-shadow: 0 12px 24px rgba(64, 158, 255, 0.25);
  margin-bottom: 20px;
}

.welcome-title {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 8px;
}

.welcome-sub {
  font-size: 14px;
  opacity: 0.9;
}

.info-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
}

/* 骨架屏 */
.skeleton-box {
  display: flex;
  gap: 16px;
}

.sk-avatar {
  width: 56px;
  height: 56px;
}

.sk-lines {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 6px;
}

.sk-line {
  width: 60%;
}

/* 用户信息 */
.user {
  display: flex;
  align-items: center;
  gap: 16px;
}

.user-avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, #409eff, #66b1ff);
  color: #fff;
  font-size: 22px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.user-name {
  font-size: 18px;
  font-weight: 600;
  color: #1f2d3d;
}

.user-email {
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
</style>