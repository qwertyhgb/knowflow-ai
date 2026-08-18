-- V11：创建 AI 会话表（ai_conversation）。
--
-- 设计意图：Phase 12 引入「AI 会话系统」。会话是消息的容器：一个用户可有多个会话，
-- 一个会话包含多条消息（见 V12 ai_message）。会话是【用户级】资源——不绑定企业、
-- 不需要 X-Enterprise-Id，与 AI 对话/语义检索接口的定位一致（「个人 AI 助手」形态，
-- 客服场景 Phase 13 再挂企业上下文）。
--
-- 为什么 title 用 VARCHAR(100) 且默认 '新对话'：
--   - 创建会话时还没有内容，标题用默认值占位，等第一条用户消息到达后再用
--     首条消息前 20 字符更新（标题生成策略见 ConversationChatServiceImpl）；
--   - 100 字符足以容纳截断后的标题，避免标题字段无限膨胀。
--
-- 为什么只建 user_id 普通索引而非外键：
--   - 会话按 user_id 查询（我的会话列表），这是最高频的查询路径，索引必须有；
--   - 不建外键是刻意决定：平台用户表 sys_user 的删除策略是「逻辑删除/禁用」而非物理删除，
--     物理外键在逻辑删除模型下收益有限，反而增加删除成本。归属校验在 Service 层完成。
--
-- 已发布的迁移脚本禁止修改；后续结构变更请新增更高版本迁移。

CREATE TABLE ai_conversation (
    id         BIGINT       NOT NULL AUTO_INCREMENT
        /*! COMMENT '会话 ID' */,
    user_id    BIGINT       NOT NULL
        /*! COMMENT '所属用户 ID（会话是用户级资源）' */,
    title      VARCHAR(100) NOT NULL DEFAULT '新对话'
        /*! COMMENT '会话标题：创建时为默认值，第一条消息后自动生成' */,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）：发消息时更新，列表按此倒序' */,

    PRIMARY KEY (id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = 'AI 会话表：用户级资源，一个用户可有多个会话' */;

-- 「我的会话列表」查询：按用户维度反查其全部会话（配合 updated_at 倒序）。
CREATE INDEX idx_ai_conversation_user_id
    ON ai_conversation (user_id);
