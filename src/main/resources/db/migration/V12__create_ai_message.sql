-- V12：创建 AI 会话消息表（ai_message）。
--
-- 设计意图：会话内的每条对话记录（用户问 + 助手答）。一条消息归属于一个会话，
-- 通过 conversation_id 关联 ai_conversation。本表是「多轮对话」的持久化底座：
-- 每轮对话把用户消息与助手消息各存一行，再次提问时读取最近 N 条作为历史上下文。
--
-- 为什么 content 用 LONGTEXT 而不是 TEXT：
--   - TEXT 上限 64KB（65535 字节）；对话内容（尤其助手回答，可能附引用/长解释）
--     可达数千字，极端场景下会逼近甚至超过 TEXT 上限；
--   - LONGTEXT 上限约 4GB，与 document.content（V10）同理——长文本统一用 LONGTEXT。
--
-- 为什么 role 用 VARCHAR(20) 存储可读枚举而非 TINYINT：
--   - 与 enterprise_invitation.status 先例一致：可读英文单词落库，语义自解释，
--     日志与数据库直查直观；后续扩展新角色（如 SYSTEM）无需迁移改表。
--   - 取值约定：USER（用户消息）/ ASSISTANT（助手消息），见 AiMessageRole 枚举。
--
-- 为什么 token 字段可空：
--   - input_tokens / output_tokens 来自模型响应的 usage 信息，不同模型/API 返回
--     usage 的能力不一（部分服务商可能缺失）。Token 统计是观察性数据，缺失时存
--     NULL 而不阻塞主流程（见 ConversationChatServiceImpl 的教学注释）。
--
-- 为什么 conversation_id 只建普通索引而非物理外键：
--   - 消息按会话查询（会话详情、历史窗口读取）是最高频路径，索引必须有；
--   - 会话删除采用「先删消息再删会话」的两步式（Service 层保证顺序），
--     不依赖物理外键级联，故与 V11 一致不建外键。
--
-- 已发布的迁移脚本禁止修改；后续结构变更请新增更高版本迁移。

CREATE TABLE ai_message (
    id              BIGINT      NOT NULL AUTO_INCREMENT
        /*! COMMENT '消息 ID' */,
    conversation_id BIGINT      NOT NULL
        /*! COMMENT '所属会话 ID，关联 ai_conversation.id' */,
    role            VARCHAR(20) NOT NULL
        /*! COMMENT '消息角色：USER-用户消息，ASSISTANT-助手消息' */,
    content         LONGTEXT    NOT NULL
        /*! COMMENT '消息内容（UTF-8），对话文本' */,
    input_tokens    INT         NULL
        /*! COMMENT '输入 token 数（LLM usage），模型未返回时为 NULL' */,
    output_tokens   INT         NULL
        /*! COMMENT '输出 token 数（LLM usage），模型未返回时为 NULL' */,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）：会话详情与历史窗口按此排序' */,

    PRIMARY KEY (id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = 'AI 会话消息表' */;

-- 按会话查询消息（会话详情 / 历史上下文读取）：会话内的消息列表页与多轮上下文加载。
CREATE INDEX idx_ai_message_conversation_id
    ON ai_message (conversation_id);
