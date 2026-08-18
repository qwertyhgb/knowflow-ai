/**
 * 文档管理 API。
 *
 * 提供文档上传、列表查询、下载、手动触发解析四个接口。
 * 所有接口都走标准请求拦截器（自动附加 token + X-Enterprise-Id）。
 *
 * 【重要：响应拦截器的解包模式】
 * http.ts 的响应拦截器对普通 JSON 请求执行"解包"：HTTP 200 且业务
 * code 为 SUCCESS 时，直接返回 result.data（业务数据本体），而不是
 * AxiosResponse。因此本模块中除下载外的函数，拿到的返回值就已经是
 * DocumentVO / DocumentVO[] 本体，不能再写 .then((res) => res.data)
 * 二次取值（那样会拿到 undefined）。
 *
 * 唯一的例外是 downloadDocument：拦截器对 responseType === 'blob'
 * 的请求放行（不检查 code、不解包），返回完整 AxiosResponse，
 * 因为文件流不是 JSON 且文件名在响应头里。
 */

import type { AxiosResponse } from 'axios'
import http from './http'
import type { DocumentVO } from './types'

// ============================================================
// 文档上传
// ============================================================

/**
 * 上传文档到指定知识库。
 *
 * 后端收到文件后会自动创建文档记录并入队异步解析，
 * 所以上传成功后文档状态为 UPLOADED，需要轮询等待 READY。
 *
 * 【返回值说明】
 * 响应拦截器已解包，此处拿到的就是 DocumentVO 本体，
 * 不是 AxiosResponse，不需要再取 .data。
 *
 * @param enterpriseId - 企业 ID（从企业上下文 store 读取）
 * @param knowledgeBaseId - 知识库 ID
 * @param file - 要上传的 File 对象
 * @returns 上传成功的文档信息（DocumentVO 本体）
 */
export function uploadDocument(
  enterpriseId: number,
  knowledgeBaseId: number,
  file: File,
): Promise<DocumentVO> {
  // 构造 FormData：浏览器会自动设置 Content-Type 为 multipart/form-data
  // 并生成正确的 boundary 分隔符，所以不要手动设置 headers['Content-Type']
  const formData = new FormData()
  formData.append('file', file)

  // 拦截器已解包：返回值即 DocumentVO 本体。
  // 类型断言是因为 http 实例的返回类型仍声明为 AxiosResponse，
  // 实际运行时已被拦截器替换为解包后的业务数据。
  return http.post(
    `/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}/documents`,
    formData,
  ) as unknown as Promise<DocumentVO>
}

// ============================================================
// 文档列表
// ============================================================

/**
 * 查询知识库下的所有文档。
 *
 * 后端按 knowledgeBaseId 过滤，返回该知识库下所有文档的只读视图。
 *
 * 【返回值说明】
 * 响应拦截器已解包，此处拿到的就是 DocumentVO[] 本体。
 *
 * @param enterpriseId - 企业 ID
 * @param knowledgeBaseId - 知识库 ID
 * @returns 文档列表（DocumentVO[] 本体）
 */
export function getDocuments(
  enterpriseId: number,
  knowledgeBaseId: number,
): Promise<DocumentVO[]> {
  return http.get(
    `/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}/documents`,
  ) as unknown as Promise<DocumentVO[]>
}

// ============================================================
// 文档下载
// ============================================================

/**
 * 下载知识库中的文档文件。
 *
 * 【与拦截器的配合关系】
 * 本函数配置 responseType: 'blob'，http.ts 的响应拦截器识别到该配置后
 * 会放行——不检查业务 code、不解包，直接返回完整 AxiosResponse。
 * 原因：文件下载的响应体是二进制流而非 JSON，没有 Result 结构，
 * 统一解包逻辑对它不适用（强行解包会把下载误判为业务失败）。
 *
 * 【为什么需要完整 AxiosResponse？】
 * 1. res.data 是 Blob 对象，调用方用 URL.createObjectURL 触发浏览器下载
 * 2. res.headers['content-disposition'] 携带服务端建议的文件名
 *    （RFC 5987 编码，支持中文），只有拿到完整响应才能读取
 * 3. res.status 可用于调用方做额外的结果判断
 *
 * @param enterpriseId - 企业 ID
 * @param knowledgeBaseId - 知识库 ID
 * @param documentId - 文档 ID
 * @returns 包含 status、headers、data(Blob) 的下载结果
 */
export function downloadDocument(
  enterpriseId: number,
  knowledgeBaseId: number,
  documentId: number,
): Promise<{ status: number; headers: AxiosResponse['headers']; data: Blob }> {
  return http
    .get(
      `/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/download`,
      { responseType: 'blob' },
    )
    .then((res) => ({
      status: res.status,
      // Content-Disposition 头包含服务器建议的文件名，格式：
      // attachment; filename="xxx" 或 attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87
      headers: res.headers,
      data: res.data as Blob,
    }))
}

// ============================================================
// 手动触发解析
// ============================================================

/**
 * 手动触发文档解析。
 *
 * 【使用场景】
 * 后端状态机规定只有 UPLOADED 状态的文档允许手动解析
 * （READY/FAILED/PARSING 调用此接口返回 409）。
 * 这是补偿入口：正常流程上传后自动入队解析，无需手动触发；
 * 只有当异步消息发布失败等异常导致文档滞留在 UPLOADED 时才需要。
 *
 * 【返回值说明】
 * 响应拦截器已解包，此处拿到的就是 DocumentVO 本体。
 *
 * @param enterpriseId - 企业 ID
 * @param knowledgeBaseId - 知识库 ID
 * @param documentId - 文档 ID
 * @returns 解析触发后的文档最新状态（DocumentVO 本体）
 */
export function parseDocument(
  enterpriseId: number,
  knowledgeBaseId: number,
  documentId: number,
): Promise<DocumentVO> {
  return http.post(
    `/enterprises/${enterpriseId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/parse`,
  ) as unknown as Promise<DocumentVO>
}
