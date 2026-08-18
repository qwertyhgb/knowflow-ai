import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../layout/Layout.vue'
import HomeView from '../views/HomeView.vue'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'
import EnterpriseView from '../views/EnterpriseView.vue'
import InvitationsView from '../views/InvitationsView.vue'
import KnowledgeBaseView from '../views/KnowledgeBaseView.vue'
import KnowledgeBaseDetailView from '../views/KnowledgeBaseDetailView.vue'
import SearchView from '../views/SearchView.vue'
import AiChatView from '../views/AiChatView.vue'
import { useUserStore } from '../stores/user'

/**
 * 路由实例。
 *
 * 【为什么业务页面套在 Layout 下、而登录/注册页不套？】
 * Layout（侧边栏 + 顶部栏）是"管理端内部"的框架壳，只有登录后才能看到。
 * 而登录/注册页是独立的公共入口，不需要侧边栏和顶部栏。
 * 用父子路由（Layout 为父、业务页为子）让子路由共享 Layout 布局，
 * 登录/注册页作为顶层路由单独存在，结构清晰、职责分明。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
    },
    {
      path: '/register',
      name: 'register',
      component: RegisterView,
    },
    {
      // 管理端整体套 Layout，子路由通过 children 声明并渲染到 Layout 的 router-view
      path: '/',
      component: Layout,
      children: [
        {
          // 子路由 path 为空字符串 '' 表示命中父路径 '/'，即首页
          path: '',
          name: 'home',
          component: HomeView,
        },
        {
          // 企业管理：挂在 Layout 下，共享侧边栏与顶部栏
          path: 'enterprise',
          name: 'enterprise',
          component: EnterpriseView,
        },
        {
          // 我的邀请：挂在 Layout 下，但它是全局入口（不依赖企业上下文），
          // 放在 Layout 下是为了继承侧边栏与顶部栏的布局框架。
          path: 'invitations',
          name: 'invitations',
          component: InvitationsView,
        },
        {
          // 知识库列表：需要选定企业后使用，知识库从属于当前企业。
          path: 'knowledge-bases',
          name: 'knowledgeBases',
          component: KnowledgeBaseView,
        },
        {
          // 知识库详情：路由参数 knowledgeBaseId，从属于当前企业。
          path: 'knowledge-bases/:knowledgeBaseId',
          name: 'knowledgeBaseDetail',
          component: KnowledgeBaseDetailView,
        },
        {
          // 全文搜索：需要选定企业后使用，搜索限定在当前企业内已解析文档。
          path: 'search',
          name: 'search',
          component: SearchView,
        },
        {
          // AI 助手：对话页。注意它与其他业务页不同——不依赖企业上下文
          // （后端 AI 接口是非企业作用域，请求无需携带 X-Enterprise-Id），
          // 因此未选定企业也能正常使用；未登录则由上方全局路由守卫拦截。
          path: 'ai-chat',
          name: 'aiChat',
          component: AiChatView,
        },
      ],
    },
  ],
})

/**
 * 全局路由守卫（beforeEach）。
 *
 * 【为什么用前端路由守卫做第一道防线？】
 * 注意：前端守卫只是"体验层的跳转控制"，不是安全边界。
 * 后端的每个接口仍然会独立校验 token，即使某人绕过前端守卫直达页面，
 * 接口请求也会因缺 token 而返回 401。前端守卫的价值在于：
 * 让用户"少看到一次 401 报错"，而是直接看到登录页，交互更顺畅。
 */
router.beforeEach((to) => {
  const userStore = useUserStore()

  // 无需登录即可访问的白名单：登录页、注册页
  const whitelist = ['/login', '/register']
  const isPublic = whitelist.includes(to.path)

  if (!userStore.isLoggedIn) {
    // 未登录访问受保护页：跳转登录页，并把原始路径带在 query.redirect，
    // 登录成功后自动回到刚才想去的页面（体验好）
    if (isPublic) return true
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 已登录再访问登录/注册页：没必要，直接回首页
  if (isPublic) return { path: '/' }

  return true
})

export default router