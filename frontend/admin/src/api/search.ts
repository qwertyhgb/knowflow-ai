import http from './http'
import type { SearchResultVO, SearchParams } from './types'

/**
 * 全文搜索接口模块。
 *
 * 搜索接口为企业作用域（路径带 /enterprises/{enterpriseId}），
 * 请求拦截器会自动附加 X-Enterprise-Id 请求头，搜索天然限定在当前企业。
 * 后端返回结果已按当前企业 + 用户可见性过滤。
 */

/**
 * 全文搜索企业内已解析文档。
 *
 * GET /api/enterprises/{enterpriseId}/search
 * query 参数：keyword（必填）、page（可选，默认1）、size（可选，默认10）。
 *
 * 【为什么用 axios 的 params 配置？】
 * axios 会把 params 对象自动序列化为 URL query string（如 ?keyword=redis&page=1&size=10），
 * 并正确编码特殊字符（如空格、中文、& 等），无需手拼字符串，避免编码遗漏导致的 bug。
 *
 * 拦截器已解包 Result，返回类型为 SearchResultVO，用 As 标注返回类型。
 */
export function searchDocuments(
  enterpriseId: number,
  params: SearchParams,
): Promise<SearchResultVO> {
  return http.get(`/enterprises/${enterpriseId}/search`, {
    params,
  })
}