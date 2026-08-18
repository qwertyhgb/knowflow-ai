import http from './http'
import type { LoginParams, LoginResult, RegisterParams, UserVO } from './types'

/**
 * 用户接口模块。
 *
 * 每个函数都带完整的 TypeScript 类型标注，调用方能获得类型提示和校验。
 * 响应拦截器已经解包了 Result，所以这里返回类型直接用 T 而不是 Result<T>。
 */

/**
 * 用户登录。
 *
 * POST /api/users/login
 * 成功返回 { token, user }，token 由调用方存入 localStorage。
 */
export function login(params: LoginParams): Promise<LoginResult> {
  return http.post('/users/login', params)
}

/**
 * 用户注册。
 *
 * POST /api/users/register
 * 成功返回新创建的用户信息。
 */
export function register(params: RegisterParams): Promise<UserVO> {
  return http.post('/users/register', params)
}

/**
 * 获取当前登录用户信息。
 *
 * GET /api/users/me
 * 需要携带 Authorization: Bearer {token} 请求头（由 axios 请求拦截器统一添加）。
 * 未登录时返回 401 UNAUTHORIZED，由响应拦截器统一处理。
 */
export function getMe(): Promise<UserVO> {
  return http.get('/users/me')
}

/**
 * 用户退出登录。
 *
 * POST /api/users/logout
 * 使当前 token 在后端 Redis 中失效。token 由请求拦截器自动附带
 * Authorization 头，无需手动传参。
 *
 * 注意：调用成功后，前端仍需调用 Pinia store 的 logout() 清空本地状态，
 * 因为后端失效的是"服务端登录态"，前端的 token 是否清除由前端自己负责。
 */
export function logout(): Promise<void> {
  return http.post('/users/logout')
}