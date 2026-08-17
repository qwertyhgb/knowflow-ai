-- V10：为 document 表补充「解析结果存储列」（content / failed_reason）。
--
-- 设计意图：文档解析（Phase 6）把 PDF/DOCX/TXT/MD 的文件内容提取为纯文本，
-- 存入 content 列，作为后续 Phase 8 全文搜索与 Phase 9+ AI/RAG 检索的数据源。
--
-- 为什么用 LONGTEXT 而不是 TEXT：
--   - TEXT 上限 64KB（65535 字节）；文档解析出的纯文本可达数 MB（一本几十页的
--     书籍或大量文字资料很容易超过 64KB），TEXT 会截断或写入失败；
--   - LONGTEXT 上限约 4GB，足以承载单个文档的解析文本，且与 MySQL 的
--     utf8mb4 编码配合良好（utf8mb4 下 VARCHAR/TEXT 的可存字符数会进一步缩水，
--     长文本更应使用 LONGTEXT）。
--
-- 为什么 failed_reason 只存固定短语：
--   - 解析失败时把原因归一化为白名单短语（如「文件格式不支持」「文件已损坏」），
--     不做字符串拼接、不存异常 message / 堆栈——遵循日志白名单原则，避免把
--     可能含敏感信息或冗长堆栈的原始异常文本写入数据库；
--   - 失败详情如需排查，由 WARN 日志承载，数据库只记录业务可读的固定原因。
--
-- 已发布的迁移脚本禁止修改；后续结构变更请新增更高版本迁移。

-- 1. 解析后的纯文本内容：NULL 表示尚未解析成功（UPLOADED/FAILED 时为空）。
ALTER TABLE document
    ADD COLUMN content LONGTEXT NULL
        /*! COMMENT '解析后的纯文本内容（UTF-8），AI/RAG 检索的数据源' */;

-- 2. 解析失败原因：只存白名单固定短语，成功为 NULL。
ALTER TABLE document
    ADD COLUMN failed_reason VARCHAR(500) NULL
        /*! COMMENT '解析失败原因（白名单短语），成功为 NULL' */;