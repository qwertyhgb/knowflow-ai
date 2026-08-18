import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import router from './router'

/**
 * 应用入口：创建 Vue 实例并依次挂载三个"地基"插件。
 * 1. Pinia —— 全局状态管理（用户登录态等）
 * 2. Router —— 页面路由
 * 3. Element Plus —— UI 组件库
 */

// 创建 Pinia 实例并在挂载前注册，保证 store 在组件中可用
const app = createApp(App)

app.use(createPinia())
app.use(router)

/**
 * 全量引入 Element Plus。
 *
 * 【为什么先全量引入？】
 * 1. 简单直观：一行 app.use(ElementPlus) 即可使用所有组件，心智负担低。
 * 2. 上手友好：学习阶段不需要关心按需引入的插件配置（unplugin-vue-components
 *    等），避免被构建配置分散注意力。
 * 3. 代价可接受：全量引入会让打包体积变大，但作为 SPA 管理后台，
 *    配合 gzip 后体积在可接受范围。
 *
 * 按需引入（配合自动导入插件）作为后续明确的「性能优化」学习主题，
 * 现阶段不引入额外配置。
 */
app.use(ElementPlus)

app.mount('#app')