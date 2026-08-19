<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  HomeFilled,
  OfficeBuilding,
  Collection,
  Document,
  Search,
  ChatDotRound,
  Tickets,
  ArrowDown,
  SwitchButton,
  Bell,
} from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user'
import { useEnterpriseStore } from '../stores/enterprise'
import { logout as apiLogout } from '../api/user'

/**
 * 管理端经典布局：左侧边栏 + 顶部栏 + 主内容区。
 *
 * 【为什么用 flex 弹性布局？】
 * 三段式（侧栏 / 顶栏 / 内容）用 display:flex + flex-direction:column 布局，
 * 让内容区自动填满剩余高度，不需要手写固定高度或计算百分比，简单且健壮。
 *
 * 【为什么菜单做成"真实路由 + 占位"两种？】
 * 首页、企业管理已接入真实页面；知识库、文档、搜索在后续步骤才实现。
 * 因此菜单项带 path（真实路由跳转路径）与 real（是否已实现）两个字段，
 * real 为 true 时跳转 path，false 时提示"功能开发中"。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const enterpriseStore = useEnterpriseStore()

/** 当前高亮菜单：以路由路径的第一段为准（'/' → home） */
const activeMenu = computed(() => route.path.split('/')[1] || 'home')

/** 当前页面标题：根据高亮菜单项推断，显示在顶部栏 */
const currentTitle = computed(
  () => menus.find((m) => m.index === activeMenu.value)?.title ?? '首页',
)

/** 侧边栏菜单项配置 */
interface MenuItem {
  index: string
  title: string
  icon: unknown
  /** 真实路由跳转路径；real=false 时该字段可忽略 */
  path?: string
  /** 是否真实可用的路由；false 表示占位项 */
  real: boolean
}

const menus: MenuItem[] = [
  { index: 'home', title: '首页', icon: HomeFilled, path: '/', real: true },
  { index: 'enterprise', title: '企业管理', icon: OfficeBuilding, path: '/enterprise', real: true },
  { index: 'knowledge', title: '知识库', icon: Collection, path: '/knowledge-bases', real: true },
  { index: 'document', title: '文档', icon: Document, real: false },
  { index: 'search', title: '搜索', icon: Search, path: '/search', real: true },
  // 工单：企业作用域，需选定企业后使用。index 取 'tickets' 与路径第一段一致，
  // 保证顶部栏标题「工单」与菜单高亮能正确匹配（activeMenu 按路径首段推导）。
  { index: 'tickets', title: '工单', icon: Tickets, path: '/tickets', real: true },
  // AI 助手：真实路由项，点击跳转 /ai-chat。放在搜索之后作为功能入口。
  { index: 'ai-chat', title: 'AI 助手', icon: ChatDotRound, path: '/ai-chat', real: true },
]

/** el-menu 选中回调：真实项跳转对应路由，占位项提示"功能开发中" */
function onMenuSelect(index: string) {
  const item = menus.find((m) => m.index === index)
  if (!item) return
  if (item.real && item.path) {
    router.push(item.path)
  } else {
    ElMessage.info(`${item.title}功能开发中`)
  }
}

/** 退出登录 */
async function handleLogout() {
  try {
    // 二次确认：防止误触，这是破坏性操作
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    // 用户点击取消，直接结束，不做任何事
    return
  }

  // 先调后端使 token 失效。`as` 重命名避免与 store.logout 同名冲突
  await apiLogout()
  // 再清空前端登录态并跳转登录页
  userStore.logout()
  // 同时清空企业上下文：避免下一位用户登录同一浏览器时残留上一个企业的上下文，
  // 导致企业作用域请求带上错误的 X-Enterprise-Id
  enterpriseStore.clearCurrentEnterpriseId()
  ElMessage.success('已退出登录')
  router.push('/login')
  // TODO: 退出后再造访受保护页会被路由守卫拦截回登录页（见 router/index.ts）
}

/**
 * 处理下拉菜单命令。
 *
 * 用 @command 统一处理，避免在模板中写多个 @click 事件。
 * 命令值对应 el-dropdown-item 的 command prop。
 */
function handleDropdownCommand(command: string) {
  if (command === 'logout') {
    handleLogout()
  } else if (command === 'invitations') {
    router.push('/invitations')
  }
}
</script>

<template>
  <div class="layout">
    <!-- ==================== 左侧边栏 ==================== -->
    <aside class="sidebar">
      <!-- Logo 区 -->
      <div class="logo">
        <span class="logo-mark">KF</span>
        <span class="logo-text">KnowFlow</span>
      </div>

      <!-- 菜单 -->
      <el-menu
        class="menu"
        :default-active="activeMenu"
        @select="onMenuSelect"
      >
        <el-menu-item v-for="item in menus" :key="item.index" :index="item.index">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </aside>

    <!-- ==================== 右侧主体 ==================== -->
    <div class="main">
      <!-- 顶部栏 -->
      <header class="topbar">
        <div class="topbar-left">
          <h2 class="page-title">{{ currentTitle }}</h2>
          <!-- 当前企业显示：
               已选定企业 → 蓝色 tag，点击进入企业管理页；
               未选定 → 灰色提示 -->
          <router-link
            v-if="enterpriseStore.hasEnterprise"
            to="/enterprise"
            class="ent-link"
          >
            <el-tag type="primary" effect="light" size="small">
              {{ enterpriseStore.currentEnterpriseName }}
            </el-tag>
          </router-link>
          <span v-else class="no-ent">未选择企业</span>
        </div>
        <el-dropdown trigger="click" @command="handleDropdownCommand">
          <div class="user">
            <el-avatar :size="32" class="avatar">
              {{ userStore.user?.nickname?.[0] ?? '末' }}
            </el-avatar>
            <span class="nickname">{{ userStore.user?.nickname ?? '未登录' }}</span>
            <el-icon class="arrow"><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="invitations">
                <el-icon><Bell /></el-icon>
                我的邀请
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>
                <el-icon><SwitchButton /></el-icon>
                退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <!-- 主内容区 -->
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
}

/* ---------------- 侧边栏 ---------------- */
.sidebar {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border-right: 1px solid #ebeef5; /* 细边框，层次更清晰 */
}

.logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 1px solid #f0f2f5;
}

.logo-mark {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, #409eff, #66b1ff);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.logo-text {
  font-size: 16px;
  font-weight: 600;
  color: #1f2d3d;
}

.menu {
  flex: 1;
  border-right: none;
  padding: 12px 8px;
  /* 菜单项圆角高亮，比 Element 默认的方块式更现代 */
  --el-menu-base-level-padding: 0;
}

/* 菜单项圆角与细腻的选中/悬浮态 */
.menu :deep(.el-menu-item) {
  height: 44px;
  border-radius: 8px;
  margin-bottom: 4px;
  color: #606266;
  font-size: 14px;
  transition: background-color 0.2s ease, color 0.2s ease;
}

.menu :deep(.el-menu-item:hover) {
  background-color: #f5f7fa;
  color: #409eff;
}

.menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(135deg, #ecf5ff, #d9ecff);
  color: #409eff;
  font-weight: 600;
}

/* ---------------- 右侧主体 ---------------- */
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  height: 56px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: #ffffff;
  border-bottom: 1px solid #ebeef5;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04); /* 顶部栏悬浮阴影，增加层次感 */
  z-index: 1;
}

.page-title {
  font-size: 17px;
  font-weight: 600;
  color: #1f2d3d;
}

/* 顶部栏左侧：标题 + 当前企业标签 */
.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ent-link {
  display: inline-flex;
  text-decoration: none;
}

.no-ent {
  font-size: 13px;
  color: #c0c4cc;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 8px;
  transition: background-color 0.2s ease;
}

.user:hover {
  background-color: #f5f7fa;
}

.avatar {
  background: linear-gradient(135deg, #409eff, #66b1ff);
  color: #fff;
  font-weight: 600;
}

.nickname {
  font-size: 14px;
  color: #303133;
}

.arrow {
  color: #909399;
  font-size: 12px;
}

.content {
  flex: 1;
  overflow-y: auto;
  background: #f5f7fa; /* 浅灰内容区，与白色侧栏/顶栏形成对比 */
  padding: 24px;
}
</style>