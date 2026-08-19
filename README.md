# KnowFlow · AI 企业知识库智能客服 SaaS

一个用于学习 Java 后端与 AI 应用开发的教学项目：企业知识库 + AI 智能客服的完整实现。
从零搭建了「文档上传 → 异步解析 → 全文/语义检索 → AI 带引用回答 → 智能客服工单」的完整链路。

## 技术栈

**后端**：Java 21 · Spring Boot 4.1 · Spring Security(随机 Token + Redis 登录态) · MyBatis-Plus 3.5 · Flyway(16 个迁移脚本) · MySQL 8.4 / H2 测试库

**基础设施**：Redis(登录态/缓存/限流/分布式锁) · RabbitMQ(异步解析 + 重试 + 死信队列) · Elasticsearch 9.4(全文搜索 + 向量检索)

**AI 应用**：Spring AI 2.0 · DeepSeek(对话/流式) · 硅基流动 bge-m3(Embedding) · RAG 检索增强生成 · SSE 流式输出

**前端**：Vue 3 · Vite · TypeScript · Element Plus · Pinia · Vue Router · Axios

**质量**：627 个自动化测试(单元 + Web 层 + 集成) · 统一异常/响应 · 日志白名单规范 · AOP 操作审计

## 功能特性

| 模块 | 能力 |
|---|---|
| 认证 | 注册 / 登录 / Token 登录态(Redis,自动续期)/ 修改密码 / 退出 |
| 企业 | 创建 / 成员管理(角色分层)/ 邮箱邀请(一次性令牌)/ 部门树 / RBAC 权限码 |
| 知识库 | 创建 / 成员角色(只读/可编辑/管理)/ 公开或私有 |
| 文档 | 上传(pdf/docx/txt/md,10MB)/ 异步解析(MQ + 重试 + 死信)/ 下载 / 状态机 |
| 搜索 | 全文搜索(ES 倒排索引,高亮命中)/ 语义搜索(向量,跨词面匹配) |
| AI 助手 | 多会话对话(历史记忆)/ 流式输出(SSE 打字机效果)/ **RAG 知识库问答 + 引用溯源** |
| 工单 | 创建(分类/优先级)/ **AI 自动回答,答不出转人工** / 分配 / 状态机流转 / 回复历史 |
| 审计 | AOP 操作日志(登录/企业/知识库/工单关键操作,参数脱敏) |
| 工程 | Redis 缓存(Cache Aside + 防穿透/雪崩)/ 限流 / 分布式锁 / 统一错误码 |

## 核心链路

```text
上传文档 → 异步解析(文本提取) → 向量化(切块 + Embedding) → 存入 ES
                                                    ↓
用户提问 → 语义检索 Top K → 阈值过滤 → 拼装 Prompt → LLM 回答 + [引用]可溯源
                                                    ↓
                                    回答不了 → Human Handoff → 人工工单流转
```

## 快速开始

**1. 基础设施(4 个容器)**

```bash
docker compose up -d     # mysql / redis / rabbitmq / elasticsearch,项目名 knowflow
```

**2. 环境变量**(AI 能力需要,不配置则 AI 接口返回 503,其余功能不受影响)

```bash
setx DEEPSEEK_API_KEY "你的key"          # https://platform.deepseek.com
setx SILICONFLOW_API_KEY "你的key"       # https://siliconflow.cn (Embedding)
```

**3. 启动后端**

```bash
.\mvnw.cmd spring-boot:run
# Swagger 文档: http://localhost:8080/swagger-ui
```

**4. 启动前端管理端**

```bash
cd frontend/admin
npm install
npm run dev        # http://localhost:5173
```

使用流程:注册 → 登录 → 创建企业 → 创建知识库 → 上传文档(等待解析完成)→ 向量化(文档详情 → AI 模块接口)→ 在「AI 助手」提问,或在「工单」提交工单体验 AI 自动应答。

## 项目结构

```text
src/main/java/io/github/qwertyhgb/knowflow/
├── common        # 统一响应 / 错误码 / 全局异常
├── auth          # 安全配置 / Token 认证 / 企业上下文过滤器
├── user          # 用户注册登录与资料
├── enterprise    # 企业 / 成员 / 邀请 / 部门 / RBAC
├── knowledge     # 知识库 / 文档 / 上传与解析
├── mq            # RabbitMQ 异步解析链路
├── search        # ES 全文搜索
├── ai            # AI 对话 / 流式 / RAG / 会话系统
├── ticket        # 工单系统(AI 自动应答 + 人工流转)
└── audit         # AOP 操作日志
frontend/admin/   # Vue3 管理端(核心闭环 + AI 助手 + 工单)
```

## 测试

```bash
.\mvnw.cmd test     # 627 个测试,0 失败
```

需要真实 MySQL/Redis 的集成测试按环境条件自动跳过,单元测试与 Web 层测试全量运行。

## 学习路线

本项目按教学文档分阶段推进(Phase 0-14 + 收尾),每阶段包含:需求 → 设计 → 实现 → 测试 → 复盘。
完整教学提示词见 `docs/AI 企业知识库 - 智能客服 SaaS 项目长期教学提示词.md`。
