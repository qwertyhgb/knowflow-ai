import { ElMessage } from 'element-plus'
import { TOKEN_STORAGE_KEY } from '../stores/user'

/**
 * AI 对话接口模块（流式 SSE）。
 *
 * 【为什么这里不用 axios，改用原生 fetch？】
 * axios 的响应处理是「等整个响应体下载完成后再一次性返回」，它会把 body 缓冲进
 * 内存，业务代码拿到的永远是完整数据——无法在数据「逐块到达」的瞬间实时处理。
 * 而 AI 流式对话的核心是「边生成边展示」（打字机效果），必须在每个分片到达的瞬间
 * 就拿到它并渲染。原生 fetch 的 response.body 是一个 ReadableStream，
 * 可以用 getReader() 逐块 read()，天然支持流式消费，这正是 axios 给不了的。
 *
 * 【为什么不用浏览器内置的 EventSource？】
 * EventSource 是浏览器原生 SSE 客户端，但它有两个硬限制：
 *   1. 只能发 GET 请求，而我们的后端接口是 POST；
 *   2. 不能自定义请求头，而我们的接口需要 Authorization: Bearer {token} 鉴权。
 * 这两条导致 EventSource 无法直接调用我们的流式接口，只能手动用 fetch 实现
 * 「读流 + 解析 SSE 帧」的逻辑——本质是把 EventSource 内部做的事重新实现一遍。
 */

/**
 * 流式回调集合。
 *
 * <p>【为什么 onEvent 是可选回调、且由调用方分流而非 chatStream 硬编码业务事件？】
 * SSE 协议约定「事件名由应用层定义」：除了默认 data 事件（无 event 名）和 error 事件，
 * 后端可能发任意业务自定义命名事件（如 RAG 的 citations）。若 chatStream 硬编码
 * citations 的解析逻辑，它就与特定业务耦合了——以后新增其他命名事件又要改这里。
 * 让 chatStream 保持「通用 SSE 客户端」职责：只按协议把「有 event 名且非 error」的帧
 * 原样透传给调用方（onEvent），由调用方判断事件名并处理。这就是「公共能力下沉、
 * 业务差异上浮」的分层。</p>
 */
export interface ChatStreamHandlers {
  /** 每收到一个回答分片时触发（默认 data 事件） */
  onChunk: (text: string) => void
  /** 流式过程出错时触发（error 命名事件或网络错误） */
  onError: (message: string) => void
  /** 流正常结束时触发 */
  onDone: () => void
  /** 业务自定义命名事件透传（可选）：收到「有 event 名且不是 error」的帧时触发 */
  onEvent?: (event: string, data: string) => void
}

/**
 * RAG 引用来源，与后端 RagCitationVO 字段一一对应。
 *
 * <p>score 为余弦相似度（0~1），可能为 null——个别向量存储实现不返回分数（后端已声明）。
 * documentId 定位来源文档、knowledgeBaseId 归属知识库（前端跳转用）、
 * chunkIndex 定位块在文档内的位置。回答中的 [1][2] 标注与 citations 数组下标一一对应。</p>
 */
export interface RagCitation {
  documentId: number
  knowledgeBaseId: number
  fileName: string
  chunkIndex: number
  chunkText: string
  score: number | null
}

/** 解析结果：完整事件列表 + 尚未构成完整帧的剩余文本 */
interface ParsedSse {
  events: { event: string | null; data: string }[]
  rest: string
}

/**
 * 把累积的文本按 SSE 帧格式解析。
 *
 * SSE 帧格式（与 EventSource 内部解析逻辑一致）：
 *   一个「事件」由若干行组成，事件之间用空行（\n\n）分隔。
 *   每行要么是 "data: xxx"，要么是 "event: xxx"（event 行可省略）。
 * 例如后端发来的三个分片在字节流里表现为：
 *   data: 你\n\n data: 好\n\n data: !\n\n
 * 而 error 事件表现为：
 *   event:error\ndata:AI 服务暂时不可用\n\n
 *
 * 【为什么这里手动实现了一遍 SSE 解析？】
 * 因为我们用 fetch 拿到的只是原始文本流，浏览器不会替我们按 SSE 协议拆帧
 * （那是 EventSource 的职责，而 EventSource 用不了，见文件头注释）。
 */
function parseSse(buffer: string): ParsedSse {
  // SSE 规范允许 LF / CRLF / CR 三种换行，先归一化成 \n 再处理，
  // 避免后端输出 \r\n 时按 '\n\n' 分割失败。
  const normalized = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n')

  // 按空行把文本切成「帧」；最后一段没有以空行结束，属于不完整帧，留到下一轮继续拼
  const frames = normalized.split('\n\n')
  const rest = frames.pop() ?? ''

  const events = frames.map((frame) => {
    let event: string | null = null
    let data = ''
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice('event:'.length).trim()
      } else if (line.startsWith('data:')) {
        // SSE 规范：data: 后若紧跟一个空格，该空格是分隔符而非内容，需去掉
        const value = line.slice('data:'.length).replace(/^ /, '')
        // 一条事件的 data 可能占多行，多行用换行拼接
        data += (data ? '\n' : '') + value
      }
    }
    return { event, data }
  })

  return { events, rest }
}

/**
 * 把解析出的单个 SSE 帧分发到对应回调（循环与 flush 共用，单一实现）。
 *
 * 三类帧的分流规则：
 * 1. error 命名事件 → onError（错误是协议级概念，由客户端统一兜底）；
 * 2. 其他命名事件（如 citations）→ onEvent（业务自定义事件，原样透传）；
 * 3. 默认 data 事件（无 event 名）→ onChunk（回答分片）。
 */
function dispatchEvent(
  e: { event: string | null; data: string },
  handlers: ChatStreamHandlers,
) {
  if (e.event === 'error') {
    // 后端以 error 命名事件发送的错误（如未配置 DEEPSEEK_API_KEY）
    handlers.onError(e.data || 'AI 服务暂时不可用，请稍后重试')
  } else if (e.event) {
    // 有 event 名且不是 error：业务自定义命名事件，交回调用方处理
    handlers.onEvent?.(e.event, e.data)
  } else {
    // 无 event 名：默认 data 事件，即回答分片
    handlers.onChunk(e.data)
  }
}

/**
 * 发起一次流式 AI 对话。
 *
 * @param message 用户消息
 * @param handlers 流式回调
 * @returns AbortController，调用方调用 .abort() 可中断本次请求
 */
export function chatStream(message: string, handlers: ChatStreamHandlers): AbortController {
  // AbortController 是取消 fetch 的标准机制：abort() 后 fetch 与 read() 都会抛 AbortError
  const controller = new AbortController()
  const token = localStorage.getItem(TOKEN_STORAGE_KEY)

  // 用立即执行的 async 函数承载整个流式流程（fetch 本身是异步的）。
  // 外层函数只负责创建 AbortController 并立即返回，让调用方能拿到它用于「停止」。
  void (async () => {
    try {
      // 手动构造请求：绕过 axios，headers 与 body 都要自己拼。
      // token 从 localStorage 读（与 http.ts 请求拦截器读的是同一个键）。
      const response = await fetch('/api/ai/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          // token 存在时才带 Authorization 头（token 为 null 时省略，交由后端返回 401）
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ message }),
        signal: controller.signal,
      })

      // ---- 401：登录态失效 ----
      // 【为什么这里要手动处理 401？】因为绕过了 axios，它的响应拦截器不会再替我们
      // 拦截 401 做提示与跳转，必须在这里补齐等价逻辑。
      if (response.status === 401) {
        ElMessage.warning('登录已过期，请重新登录')
        handlers.onError('登录已过期，请重新登录')
        // 清除失效 token：否则跳转登录页后，路由守卫读到残留 token 会误判为
        // 「已登录」又把用户重定向回首页，形成「回首页 → 401 → 跳登录 → 回首页」的死循环。
        localStorage.removeItem(TOKEN_STORAGE_KEY)
        // 稍作延迟再跳转，让用户能看清提示；立即跳会瞬间切页看不到提示文案
        setTimeout(() => {
          window.location.href = '/login'
        }, 800)
        return
      }

      // ---- 非 200（如 400 参数错误、503 AI 不可用）----
      if (!response.ok) {
        // 后端错误体是 JSON（{ code, message, data }），尝试读出 message；
        // 读不到（响应体不是 JSON）就用通用文案兜底。
        let errorMessage = 'AI 服务暂时不可用，请稍后重试'
        try {
          const body = (await response.json()) as { message?: string }
          if (body?.message) errorMessage = body.message
        } catch {
          // 响应体不是 JSON，忽略解析错误，用默认文案
        }
        handlers.onError(errorMessage)
        return
      }

      // ---- 200：进入流式读取 ----
      // response.body 是 ReadableStream（SSE 是流式 HTTP 响应，body 一定存在）
      const reader = response.body!.getReader()

      // 【为什么 TextDecoder 实例要创建在循环外、且要传 { stream: true }？】
      // fetch 的 read() 按「块」返回 Uint8Array（字节），块的边界与字符边界不一定对齐：
      // 一个中文汉字占 3 个 UTF-8 字节，可能被 TCP 分包拆成两块（前 1 字节在这块、
      // 后 2 字节在下一块）。如果每块都 new 一个 TextDecoder 单独解码，遇到被拆开的
      // 多字节字符就会解码成乱码（替换符 �）。TextDecoder 的流式模式（stream: true）
      // 会记住上次没解码完的字节，下次 decode 时自动拼上，从而正确跨块还原字符。
      // 所以：解码器必须复用同一个实例，且一直以 stream: true 解码。
      const decoder = new TextDecoder('utf-8')
      // 累积尚未解析出完整 SSE 帧的文本
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        // 把本块字节追加解码（stream: true），并拼到 buffer 里
        buffer += decoder.decode(value, { stream: true })

        // 解析出完整帧；剩余不完整帧留在 buffer 里等下一块继续拼
        const { events, rest } = parseSse(buffer)
        buffer = rest

        for (const e of events) {
          dispatchEvent(e, handlers)
        }
      }

      // 读完后 flush：把 decoder 里可能残留的最后字节解出来（不传 stream 表示结束），
      // 并把 buffer 里可能残留的最后一帧也解析掉。
      buffer += decoder.decode()
      const { events } = parseSse(buffer)
      for (const e of events) {
        dispatchEvent(e, handlers)
      }

      handlers.onDone()
    } catch (err) {
      // 用户主动停止：fetch 被 abort，read() 抛 AbortError。
      // 这是正常交互而非错误，静默返回即可（View 层已在停止时自行复位状态）。
      if ((err as Error)?.name === 'AbortError') {
        return
      }
      // 其他异常（网络中断等）：提示通用错误
      handlers.onError('网络异常，请稍后重试')
    }
  })()

  return controller
}

/** RAG 流式回调：在通用回调基础上增加 citations 引用来源回调 */
export interface RagChatStreamHandlers extends ChatStreamHandlers {
  /** 收到 citations 命名事件（引用来源数组，先于回答到达）时触发 */
  onCitations?: (citations: RagCitation[]) => void
}

/**
 * 发起一次流式 RAG 对话（知识库问答）。
 *
 * <p>【为什么单独封装 ragChatStream 而不是复用 chatStream？】
 * 两个函数的请求路径、请求体、回调语义都不同：
 * chatStream → POST /api/ai/chat/stream，body 是 { message }；
 * ragChatStream → POST /api/ai/rag/chat/stream，body 是 { question, topK, scoreThreshold }，
 * 且多一个 citations 引用来源语义。
 * 但底层的「fetch 读流 + TextDecoder 流式解码 + SSE 拆帧 + 401 处理 + AbortController」
 * 完全一致——这部分由 parseSse / dispatchEvent / 公共读流逻辑承担，ragChatStream 只表达
 * RAG 特有的差异（路径、body、把 citations 事件 JSON.parse 后回调 onCitations）。
 * 这就是「公共能力下沉、业务差异上浮」的分层：通用客户端能力只写一遍，业务函数薄薄一层。</p>
 *
 * @param question        用户问题
 * @param topK            召回候选数（1~20）
 * @param scoreThreshold  相似度阈值（0~1）
 * @param handlers        回调（含可选的 onCitations）
 * @returns AbortController，调用方调用 .abort() 可中断本次请求
 */
export function ragChatStream(
  question: string,
  topK: number,
  scoreThreshold: number,
  handlers: RagChatStreamHandlers,
): AbortController {
  // AbortController 是取消 fetch 的标准机制：abort() 后 fetch 与 read() 都会抛 AbortError
  const controller = new AbortController()
  const token = localStorage.getItem(TOKEN_STORAGE_KEY)

  // 与 chatStream 相同的结构：立即执行 async 函数承载整个流式流程，
  // 外层只负责创建并返回 AbortController。

  // 组合回调：把「citations 命名事件 → JSON.parse → onCitations」这一 RAG 特有的
  // 语义在 ragChatStream 内部消化，调用方只需要传一个 onCitations，不必关心
  // citations 是从哪个命名事件来的；其他命名事件仍转发给调用方的 onEvent。
  const combinedHandlers: ChatStreamHandlers = {
    onChunk: handlers.onChunk,
    onError: handlers.onError,
    onDone: handlers.onDone,
    onEvent: (event, data) => {
      if (event === 'citations') {
        // citations 事件的 data 是引用来源数组的 JSON 字符串，需 parse 后交给调用方。
        // 用 try-catch 兜底：若后端返回的 JSON 意外损坏，宁可丢弃引用也不能让页面崩掉。
        try {
          handlers.onCitations?.(JSON.parse(data) as RagCitation[])
        } catch {
          // 忽略解析错误：引用展示失败不应中断回答流
        }
      } else {
        // 其他业务命名事件：原样透传给调用方
        handlers.onEvent?.(event, data)
      }
    },
  }

  void (async () => {
    try {
      const response = await fetch('/api/ai/rag/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          // token 存在时才带 Authorization 头（token 为 null 时省略，交由后端返回 401）
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        // 请求体：与 chatStream 的 { message } 不同，RAG 需要检索参数
        body: JSON.stringify({ question, topK, scoreThreshold }),
        signal: controller.signal,
      })

      // ---- 401：登录态失效（与 chatStream 相同的处理）----
      if (response.status === 401) {
        ElMessage.warning('登录已过期，请重新登录')
        handlers.onError('登录已过期，请重新登录')
        // 清除失效 token，避免跳转登录页后路由守卫误判已登录形成死循环
        localStorage.removeItem(TOKEN_STORAGE_KEY)
        setTimeout(() => {
          window.location.href = '/login'
        }, 800)
        return
      }

      // ---- 非 200（如 400 参数错误、503 AI 不可用）----
      if (!response.ok) {
        let errorMessage = 'AI 服务暂时不可用，请稍后重试'
        try {
          const body = (await response.json()) as { message?: string }
          if (body?.message) errorMessage = body.message
        } catch {
          // 响应体不是 JSON，忽略解析错误，用默认文案
        }
        handlers.onError(errorMessage)
        return
      }

      // ---- 200：进入流式读取 ----
      const reader = response.body!.getReader()
      // 【为什么 TextDecoder 实例要创建在循环外、且要传 { stream: true }？】
      // 与 chatStream 同理：一个中文汉字可能被 TCP 分包拆到两个 chunk，
      // 流式模式会缓存不完整字节序列、下次自动拼接，复用同一实例才能正确跨块还原字符。
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const { events, rest } = parseSse(buffer)
        buffer = rest

        for (const e of events) {
          // 复用通用分发：error → onError、默认事件 → onChunk、
          // 其他命名事件（含 citations）→ onEvent（即下面的 citations 判断逻辑）
          dispatchEvent(e, combinedHandlers)
        }
      }

      // 读完后 flush：把 decoder 残留字节与 buffer 里的最后一帧都处理掉
      buffer += decoder.decode()
      const { events } = parseSse(buffer)
      for (const e of events) {
        dispatchEvent(e, combinedHandlers)
      }

      handlers.onDone()
    } catch (err) {
      // 用户主动停止：静默返回（View 层已自行复位状态）
      if ((err as Error)?.name === 'AbortError') {
        return
      }
      // 其他异常（网络中断等）：提示通用错误
      handlers.onError('网络异常，请稍后重试')
    }
  })()

  return controller
}
