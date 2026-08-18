import axios, { type AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import type { Result } from './types'
import { TOKEN_STORAGE_KEY } from '../stores/user'
import { ENTERPRISE_STORAGE_KEY } from '../stores/enterprise'

/**
 * 创建 axios 实例，baseURL 设为 '/api'。
 *
 * 【为什么 baseURL 是 '/api' 而不是完整 URL？】
 * 开发环境：Vite dev server 代理以 /api 开头的请求到 localhost:8080，
 *   浏览器实际请求的是 http://localhost:5173/api/xxx，Vite 代理转发到后端。
 * 生产环境：前端静态资源与后端部署在同域下（Nginx 反向代理或后端内嵌），
 *   直接请求同域下的 /api/xxx 即可。
 * 因此 baseURL 写 '/api' 在两种环境下都正确，不需要环境变量区分。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

/**
 * 请求拦截器：自动附带 Authorization 请求头。
 *
 * 从 localStorage 读取 token，如果存在则自动注入到每个请求的 Authorization 头中。
 * 这样业务代码调用 API 函数时不需要每次都手动传 token。
 *
 * 读取 localStorage 而非从 Pinia store 导入，是因为 store 依赖 Pinia 实例，
 * 而 Pinia 实例是在 main.ts 中通过 app.use(createPinia()) 创建的，
 * 在模块加载阶段有可能尚未初始化，直接 import store 会导致循环依赖或 undefined。
 * 直接从 localStorage 读取是更安全、更简单的做法。
 */
http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_STORAGE_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  // 企业上下文：若已选定企业，自动附加 X-Enterprise-Id 请求头。
  // 也用 localStorage 读取（与 token 同一模式），规避模块加载期 Pinia 未初始化问题。
  const enterpriseId = localStorage.getItem(ENTERPRISE_STORAGE_KEY)
  if (enterpriseId) {
    // 值必须是纯数字字符串——后端把 X-Enterprise-Id 按 Long 解析，
    // 传 "123" 而非带引号的格式，否则会转失败。
    config.headers['X-Enterprise-Id'] = enterpriseId
  }

  return config
})

/**
 * 响应拦截器：统一解包与错误处理。
 *
 * 【为什么在这一层统一处理？】
 * 1. 职责分离：业务代码只关心 data，不需要每次都写 if (res.data.code !== 'SUCCESS')
 *    的样板代码，也不需要每个请求都 try-catch 来提示错误。
 * 2. 全局一致性：401 提示、网络错误提示等全局行为集中在一处，不会因为某个开发
 *    忘记处理而导致用户体验不一致。
 * 3. 可维护性：将来要改提示文案或加全局 Loading 动画，只需要改这里。
 *
 * 处理逻辑：
 * - HTTP 200 + 业务 code SUCCESS → 直接返回 data（不包装，业务代码拿到的就是 T）
 * - HTTP 200 + 业务 code 非 SUCCESS → 抛出带 message 的业务错误
 * - HTTP 401 → 提示"登录已过期"（后续配合 Router 守卫跳转到登录页）
 * - 网络/超时错误 → 提示"网络异常"
 */
http.interceptors.response.use(
  (response) => {
    // 【blob 下载请求必须放行，不走统一解包】
    // 文件下载的响应体是二进制流，不是 JSON，没有 Result { code, message, data }
    // 结构——强行按 Result 解析时 result.code 是 undefined，会被下面的
    // 非 SUCCESS 分支误判为业务失败而 reject('未知错误')，导致下载永远失败。
    // 而且下载函数还需要从响应头 Content-Disposition 读取文件名、
    // 从 status 判断结果，这些都在 AxiosResponse 上，一旦解包就丢失了。
    // 所以对 responseType === 'blob' 的请求直接返回完整 AxiosResponse。
    if (response.config.responseType === 'blob') {
      return response
    }

    const result = response.data as Result

    // 业务成功：直接返回 data 字段，让业务代码能直接拿到数据。
    // 注意：axios 拦截器要求返回 AxiosResponse，这里用 `as any` 绕过类型检查，
    // 因为业务代码不需要 AxiosResponse 包装层，只关心解包后的 data 内容。
    if (result.code === 'SUCCESS') {
      return result.data as any
    }

    // 业务失败：把后端 message 抛出去，让调用方 catch 后处理
    // 注意：这里不自动弹提示，因为有些场景（如登录失败）需要调用方自己决定如何展示
    return Promise.reject(new Error(result.message || '未知错误'))
  },
  (error: AxiosError<Result>) => {
    /**
     * HTTP 层错误（非 200 响应）。
     *
     * 核心原则：凡后端返回了 body（{ code, message, data }），就统一把
     * 抛出的 Error.message 设成后端 message，让所有调用方 `error.message`
     * 拿到的都是中文业务文案（如"邮箱或密码错误"），而不是 axios 原生的
     * "Request failed with status code 401"这类英文原始信息。
     */
    const status = error.response?.status
    // 后端业务 message（参数校验、账密错误、权限不足等）
    const backendMessage = error.response?.data?.message

    // 登录请求的 401：语义是"账密错误"，不是"登录过期"。
    // 此处不弹全局提示，而是把后端 message（邮箱或密码错误）抛给登录页，
    // 由登录页自己决定如何展示，避免出现误导性的"登录已过期"。
    if (status === 401 && error.config?.url === '/users/login') {
      return Promise.reject(new Error(backendMessage || '登录失败'))
    }

    // 其他请求的 401：表示登录态已失效。
    // 【为什么 401 要统一拦截提示？】
    // 后端所有需认证接口在未登录/Token 过期时都返回 401，统一在这里拦截
    // 弹出提示，后续配合路由守卫引导重新登录。
    if (status === 401) {
      ElMessage.warning('登录已过期，请重新登录')
      return Promise.reject(new Error(backendMessage || '请重新登录'))
    }

    // 其他 HTTP 层错误（400/403/404/409/500…）：优先展示后端 message
    if (backendMessage) {
      ElMessage.error(backendMessage)
      return Promise.reject(new Error(backendMessage))
    }

    // 网络超时、断网等没有后端响应的场景
    ElMessage.error('网络异常，请稍后重试')
    return Promise.reject(error)
  },
)

export default http