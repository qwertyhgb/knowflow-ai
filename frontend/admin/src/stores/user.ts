import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { UserVO } from '../api/types'

/**
 * 本地存储中保存 token 的键名。
 *
 * 独立成常量导出，供 http.ts 的请求拦截器读取，避免两处硬编码字符串不一致。
 */
export const TOKEN_STORAGE_KEY = 'knowflow_token'

/**
 * 用户状态管理 store。
 *
 * state 包含 token 和 user，token 使用 localStorage 持久化。
 *
 * 【为什么 token 存 localStorage 而不是 Cookie？】
 * 1. 防 CSRF：Cookie 会被浏览器在每次请求时自动携带，攻击者可以利用这一点跨站伪造请求
 *    （CSRF）。而 localStorage 只能被 JS 显式读取，并在请求头里手动附加，
 *    前端主动维护，从机制上规避了 Cookie 自动携带带来的 CSRF 风险。
 * 2. 前后端分离场景：token 由前端主动放入 Authorization 请求头（Bearer 方案），
 *    不依赖后端的 Set-Cookie。这是 SPA + 后端 API 分离架构的常见取舍。
 * 3. 简单直观：存储、读取、清除都很直接，学习阶段容易理解。
 *
 * 代价：localStorage 存在 XSS 风险（一旦脚本注入就能读到 token）。
 * 生产级会进一步用 httpOnly Cookie 或其他方案，此处作为学习阶段的明确取舍记录在案。
 */
export const useUserStore = defineStore('user', () => {
  /**
   * token。
   * 初始化时从 localStorage 读取，实现"刷新页面后登录态不丢失"。
   */
  const token = ref<string | null>(localStorage.getItem(TOKEN_STORAGE_KEY))

  /**
   * 当前登录用户信息。
   * 只保存在内存中，刷新后需要重新调 getMe() 获取，
   * 避免把用户信息也冗余持久化到本地。
   */
  const user = ref<UserVO | null>(null)

  /**
   * 是否已登录。
   * 以 token 是否存在为准（而非 user），因为 token 是鉴权的真实依据。
   */
  const isLoggedIn = computed(() => !!token.value)

  /** 设置 token 并持久化到 localStorage（登录成功时调用） */
  function setToken(newToken: string) {
    token.value = newToken
    localStorage.setItem(TOKEN_STORAGE_KEY, newToken)
  }

  /** 设置当前用户信息（登录成功后随 setToken 一起调用） */
  function setUser(newUser: UserVO) {
    user.value = newUser
  }

  /**
   * 退出登录：清除内存态与持久态两处 token。
   *
   * 【为什么两处都要清？】
   * - 内存态（token/user ref）：Pinia 是运行期的唯一数据源，不清会导致当前页面
   *   组件读到"已登录"的假像。
   * - 持久态（localStorage）：不清会导致刷新页面后 token 从 localStorage 复活，
   *   isLoggedIn 又变回 true，登录态"死而复活"。
   * 两者必须保持一致：只清一处都会让登录态判断失准。
   */
  function logout() {
    token.value = null
    user.value = null
    localStorage.removeItem(TOKEN_STORAGE_KEY)
  }

  return { token, user, isLoggedIn, setToken, setUser, logout }
})