# AGENTS.md

KnowFlow 项目的**规范与约定**清单，用于约束代码与 AI 协作的产出。本文件随项目演进按需追加条目（后续架构、命名、提交规范等）。

---

## 学习项目优先原则

- KnowFlow 当前首先用于学习 Java 后端，不以真实生产环境为目标；代码应优先保证容易理解、容易调试、能够由学习者自己重新写出。
- 每次新增业务优先沿用 Controller → Service → Mapper 的主线，只在当前功能确实需要时引入新框架、新抽象或安全增强。
- 只有一个实现且暂时没有替换需求的简单组件，默认不额外拆分接口与实现类；`UserService` 这类用于学习标准业务分层的接口可以保留。
- 暂不提前实现多租户、复杂权限、分布式锁、消息队列、缓存一致性、链路追踪等生产级能力；出现明确学习目标后再逐项引入。
- 保留能够帮助理解代码流程和设计原因的中文注释；修改代码时同步维护注释，禁止留下与实际实现不一致的说明。
- 安全底线仍需遵守：密码不保存明文、不记录密码或 Token、接口参数需要校验、登录态需要设置过期时间。

---

## 时间字段与 API 时间规范

> 本阶段只定规范，**不创建 `BaseEntity` 等承载类**；待实体落地时套用以下约定。

### 1. 时间类型约定

- **系统时间统一优先使用 `Instant`**（`java.time.Instant`，绝对时间轴上的瞬时点，自带 UTC 语义、无时区歧义）。
  - 典型字段一律用 `Instant`：`createdAt`、`updatedAt`、`loginAt`、`uploadedAt` ……。
  - 内部数据流全程保持 UTC，不在中间层做时区转换；时区处理推迟到展示层。
- **业务上明确属于“当地时间”的场景以后再考虑**，现阶段不预置使用：
  - `LocalDate`（仅日期，如生日）
  - `LocalDateTime`（日期 + 当地时间，如按场地所在时区排程）
  - 出现这类需求时，单独评估其入参/出参/存储方式；不要把 `LocalDateTime` 当作 `Instant` 的默认替代。
- 新代码一律走 `java.time`，不混用 `java.util.Date` / `java.util.Calendar`。

### 2. API 时间格式

- 对外 API 时间字段**统一为 ISO-8601 UTC 字符串**：
  - 例：`"createdAt": "2026-08-13T07:30:25Z"`
  - 日期与时间以 `T` 分隔，结尾 `Z` 表示 UTC 零偏移，不带 `+08:00` 之类偏移量。
- **禁止自定义或区域化格式**，例如不写成：
  - `2026/08/13 15:30:25` —— 斜杠/空格分隔、无 `Z`，且隐含本地时区
  - `08-13-2026` —— 月/日顺序歧义的区域格式
- 默认精度为**毫秒**；无小数秒时可省略小数部分。示例：`2026-08-13T07:30:25.123Z`、`2026-08-13T07:30:25Z`。
- 后端负责序列化与反序列化；前端展示时可转换为用户时区，但提交未修改的时间字段时应原样回传，禁止手工拼接时间字符串。

### 3. 存储与转换边界

- 数据库落地时，`Instant` 对应使用能够表达 UTC 瞬时点的类型；以选定数据库及驱动的实际映射为准，并通过集成测试验证读写后时间点不发生偏移。
- 业务层、持久层和 DTO 之间传递 `Instant`，不要为了格式化而转换为 `String`；仅在 API 序列化和界面展示边界处理字符串。
- 禁止使用 JVM 默认时区进行隐式转换；需要当前时间时使用可注入的 `Clock`，便于测试与统一时区控制。

### 4. 实现与验证（待实体/API 落地时执行）

- Spring Boot 与 Jackson 的默认序列化行为可能随主版本和依赖变化；不要假设默认输出。首次增加时间字段时，必须增加 Web 层测试，断言响应为第 2 节约定的 UTC 字符串，并断言相同格式可被反序列化。
- 如默认行为不符合约定，再使用当前 Jackson 主版本支持的配置或 `JsonMapper` 自定义器调整序列化；配置完成后保留上述回归测试。

---

## DTO / VO / Entity 边界规范

> 本阶段只冻结分层与命名规则；**不创建代码、空目录、`Base*` 基类或转换工具类**。待真实业务模块落地时再按本节约定组织。

### 1. 对象职责与流向

- **Request DTO**：客户端 → Controller，用于接收并校验特定接口的请求参数。
  - 示例：`UserRegisterRequest`、`UserLoginRequest`、`UserUpdateRequest`。
- **Response VO**：Controller → 客户端，用于定义特定场景下的响应数据。
  - 示例：`UserVO`、`UserProfileVO`。
- **Entity**：Mapper ↔ MySQL，用于数据库持久化映射。
  - 示例：`User`。

### 2. 强制边界

- **Entity 不直接返回给前端**。
- **Request DTO 不传入 Mapper**。
- **VO 不用于数据库持久化**。
- Controller 负责请求 DTO 与业务调用的衔接；Service 负责业务规则；Mapper 只处理 Entity 的持久化。

### 3. 命名规则

- 名称必须表达具体用途，优先使用“资源名 + 操作/场景 + 类型后缀”的形式。
  - 请求对象：`UserRegisterRequest`、`UserLoginRequest`、`UserUpdateRequest`。
  - 响应对象：`UserVO`、`UserProfileVO`。
  - 持久化对象：`User`。
- 禁止创建语义模糊的万能对象，例如：`UserDTO`、`UserInfo`、`UserData`。

### 4. 模块组织（未来真实模块落地时采用）

```text
user
├── controller
├── service
├── mapper
├── entity
├── dto
│   └── request
└── vo
```

- 当前**不要创建上述空目录**。
- 当前**不要创建** `BaseDTO`、`BaseVO`、`BaseEntity` 或通用 `Converter` 工具类；出现真实、重复且稳定的需求后再评估抽象。

---

## 日志基础规范

### 1. 输出与环境策略

- 应用日志只输出到**标准输出**，由容器、云平台或进程管理器统一采集；当前不配置本地日志文件、滚动策略或网络日志 Appender。
- 日志时间统一使用 UTC，格式为 ISO-8601；由 `application.yml` 的控制台模式保证。
- `dev` 环境允许项目包 `io.github.qwertyhgb.knowflow` 输出 `DEBUG`；`prod` 环境项目包最低为 `INFO`，根日志最低为 `WARN`。禁止将 `org.springframework`、`org.mybatis` 等第三方包长期设为 `DEBUG`。

### 2. API 与内容安全

- 使用 SLF4J：优先 `@Slf4j`，或显式 `LoggerFactory`。禁止 `System.out`、`System.err`、`printStackTrace()`。
- 不记录密码、验证码、访问令牌、Cookie、`Authorization` 请求头、密钥、数据库连接串、完整请求体，或未经脱敏的个人信息。
- 不直接记录用户提交的自由文本、异常 message 或对象 `toString()`；只记录经过白名单筛选的标识、枚举值、计数和技术上下文。
- 日志使用参数化占位符，禁止字符串拼接：`log.info("event=user_created userId={}", userId)`。

### 3. 级别与异常规则

- `TRACE`：仅用于短期、极高频的诊断，默认关闭。
- `DEBUG`：开发调试信息，不记录敏感数据；不得作为生产排障的唯一依据。
- `INFO`：应用启动、配置摘要（不含密钥）及关键业务状态变化。
- `WARN`：可预期的异常输入、降级或可恢复问题，通常不输出完整堆栈。
- `ERROR`：未预期且需要人工排查的失败；在最接近根因的位置记录一次完整堆栈，调用链上禁止重复打印。
- Controller、Service 与全局异常处理器不得对同一失败重复记 `ERROR`；当前由全局异常处理器记录未捕获异常的完整堆栈。

### 4. 格式与后续演进

- 采用可检索格式：事件名优先，随后放稳定字段，例如 `event=document_created documentId={} tenantId={}`。
- 事件名使用小写 `snake_case`；字段名使用 `camelCase`；字段值必须来自安全白名单。
- 业务 API 落地后，再增加请求/链路标识并写入 MDC；届时要求所有异步任务正确传递 MDC。当前不提前创建相关 Filter 或工具类。

---

## SQL 脚本规范

### 1. 存放位置与命名

- 所有数据库结构脚本统一存放于 `src/main/resources/db/schema/`，不散落在业务模块或项目根目录。
- 文件名使用三位递增序号与清晰动作：`001_create_sys_user.sql`、`002_add_status_to_document.sql`。
- 序号一经使用不得修改或复用；后续变更必须新增脚本，禁止直接修改已经执行过的历史脚本。
- 当前目录仅用于版本管理和人工执行，项目尚未接入 Flyway/Liquibase，应用启动时不会自动执行其中的 SQL。

### 2. 建表与字段约定

- 表名、字段名、索引名统一使用小写 `snake_case`；表名按模块使用明确前缀，例如 `sys_user`。
- 每个表必须显式声明 `ENGINE = InnoDB`、`DEFAULT CHARSET = utf8mb4`、`COLLATE = utf8mb4_0900_ai_ci`，并为表和字段添加中文 `COMMENT`。
- 主键默认使用 `BIGINT UNSIGNED NOT NULL AUTO_INCREMENT`；业务需要 UUID 时另行评估，不混用主键策略。
- 涉及时间点的字段使用 `DATETIME(3)`，以 UTC 语义写入；命名使用 `created_at`、`updated_at`，与 Java `Instant` 对应。
- 密码字段只保存哈希值，字段命名为 `password_hash`，禁止保存明文密码。

### 3. 索引与变更规则

- 业务唯一性必须使用唯一索引保证，例如邮箱使用 `UNIQUE KEY`；查询条件明确且高频时再添加普通索引。
- 索引名采用 `uk_表名_字段`（唯一索引）与 `idx_表名_字段`（普通索引）的格式。
- 不使用 `CREATE TABLE IF NOT EXISTS` 或静默忽略错误的写法，避免掩盖环境结构漂移。
