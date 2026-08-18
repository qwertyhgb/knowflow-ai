import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],

  /**
   * Vite dev server 配置。
   *
   * 【为什么用 dev server 代理而不是让后端开 CORS？】
   * 1. 同源策略：开发期浏览器访问 localhost:5173，代理将 /api 请求转发到
   *    localhost:8080，浏览器视角下所有请求都是同源的，不存在跨域问题。
   * 2. 生产环境等价：上线后前端静态资源部署到 Nginx 或后端内嵌，同样走同源策略，
   *    代理方案与生产环境一致，不需要在开发期额外配置 CORS 然后上线再移除。
   * 3. 安全：后端不需要向任何来源敞开 CORS，减少安全风险面。
   * 4. 这是 Vue 项目开发期标准做法，也是 Vite 官方推荐的方案。
   *
   * 【为什么不需要 rewrite 路径？】
   * 后端所有接口路由都以 /api 开头（如 /api/users/login），Vite 代理只需
   * 按前缀匹配将 /api/** 的请求转发到后端即可，不需要去掉 /api 前缀。
   */
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 不配置 rewrite，因为后端路由本身就以 /api 开头
      },
    },
  },
})