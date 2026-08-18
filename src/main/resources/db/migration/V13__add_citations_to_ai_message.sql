-- V13：为 ai_message 表添加 citations 列（引用来源 JSON 数组）。
--
-- 设计意图：会话内 RAG 对话的引用落库——用户在会话里问文档相关问题时，
-- 助手回答基于检索到的知识库块，回答中的 [1][2] 引用标注对应的来源信息
-- 需要持久化，以便重开会话时历史消息仍能展示引用来源（前端的「查看原文」）。
--
-- 为什么用 LONGTEXT 存 JSON 字符串而非专用 JSON 列类型：
--   - 引用数组大小不定（0 ~ topK 项），每项包含文档 ID / 文件名 / 块文本等，
--     序列化后的 JSON 字符串可达数 KB（如 topK=20 且每块包含几百字文本）；
--   - MySQL 原生支持 JSON 列类型（有 JSON 函数可查询），但 H2 测试库不兼容
--     （H2 把 JSON 列当作 CLOB 处理，导致 MyBatis-Plus 序列化行为不一致）；
--   - 教学阶段遵循「双兼容规范」（MySQL 生产库与 H2 测试库共用同一迁移脚本），
--     用文本列统一存储，序列化与反序列化由 Java 应用层处理（Jackson）。
--
-- 为什么 citations 列可空：
--   - 引用仅出现在「助手消息 + RAG 对话」的交集场景：用户消息不会有引用，
--     非 RAG 接口的助手消息（如 /api/ai/conversations/{id}/messages 普通对话）
--     也不会有引用，均存 NULL；
--   - 只有在会话内 RAG 接口（本步新增）的助手回答才存储引用 JSON，
--     已发布的历史消息无引用不受影响。
--
-- 为什么 ALTER TABLE 时必须放在 /*! COMMENT ... */ 之前：
--   - MySQL 可执行注释 /*! ... */ 用于双兼容（MySQL 执行，H2 忽略）；
--   - 列属性（类型/约束/默认值）必须放在 COMMENT 之前，否则 MySQL 解析失败。
--
-- 已发布的迁移脚本禁止修改；本迁移按 Flyway 规范递增版本号（V13）。

ALTER TABLE ai_message
    ADD COLUMN citations LONGTEXT NULL
        /*! COMMENT '引用来源（JSON 数组，仅 ASSISTANT 消息在 RAG 对话时存储，
                     结构对应 List<RagCitationVO>，用于历史会话回溯引用）' */;

