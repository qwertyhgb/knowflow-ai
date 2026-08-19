-- V15:创建工单回复表 ticket_reply。
--
-- 设计意图:工单的处理过程是多方对话流——客户补充说明(USER)、AI 基于知识库的
-- 自动回答(AI)、人工客服的处理回复(SUPPORT)。回复单独建表而非塞进工单表,
-- 是「一对多」关系的标准建模:一条回复一行,天然支持追加与按时间排序回放。
-- 本步只有 AI 回复(AI 自动回答/转人工提示),USER/SUPPORT 是后续步骤使用。
--
-- 为什么 sender_id 可空(NULL):
--   - USER/SUPPORT 回复有对应的登录用户,sender_id 记录「谁说的」;
--     AI 回复没有用户身份——AI 是系统服务,不是 sys_user 表里的任何账号,
--     强行挂一个「AI 虚拟用户」反而引入假数据。role='AI' 时 sender_id 恒为 NULL,
--     「谁说的」由 role 表达。
--
-- 为什么 AI 回复也存 citations(与 ai_message.citations 同一设计):
--   - AI 回答基于检索到的知识库块,回答中的 [1][2] 引用标注对应的来源信息
--     (文档/文件名/块文本/相似度)需要持久化——工单 reopen 或客服后期查看时,
--     仍能溯源「AI 当时依据什么作答」。引用可溯源是建立对 AI 信任的基础,
--     客服接手时也能顺藤摸瓜核对答案。
--   - 用 LONGTEXT 存 JSON 字符串而非 JSON 列类型:引用数组大小不定(0 ~ topK 项),
--     H2 测试库对 JSON 列按 CLOB 处理会导致行为不一致,文本列双端统一,
--     序列化由 Java 层(Jackson)完成(与 V13 同一取舍)。
--
-- 双兼容说明:MySQL 特有语法用 /*! ... */ 包裹,MySQL 执行、H2 忽略。
-- 已执行的迁移脚本禁止修改;后续变更新增 V{n+1}。

CREATE TABLE ticket_reply (
    id         BIGINT      NOT NULL AUTO_INCREMENT
        /*! COMMENT '回复 ID' */,
    ticket_id  BIGINT      NOT NULL
        /*! COMMENT '所属工单 ID(工单详情按 created_at 升序回放全部回复)' */,
    role       VARCHAR(20) NOT NULL
        /*! COMMENT '回复角色(USER=用户/AI=AI助手/SUPPORT=客服,前端按角色渲染气泡)' */,
    sender_id  BIGINT      NULL
        /*! COMMENT '回复人用户 ID(USER/SUPPORT 回复记录谁说的;AI 回复无用户身份,恒为 NULL)' */,
    content    LONGTEXT    NOT NULL
        /*! COMMENT '回复内容(AI 回答/客户补充/客服处理回复,长度不可预估故用 LONGTEXT)' */,
    citations  LONGTEXT    NULL
        /*! COMMENT 'AI 回答的引用来源 JSON(结构对应 List<RagCitationVO>,仅 AI 回复可能有;转人工提示无引用存 NULL)' */,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间(UTC):工单详情按此升序排列' */,

    PRIMARY KEY (id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '工单回复表:工单下的多方对话流(AI 自动回答/用户补充/客服处理,Phase 13)' */;

-- 工单详情页查询:按工单维度取全部回复(配合 created_at 升序回放)。
CREATE INDEX idx_ticket_reply_ticket_id
    ON ticket_reply (ticket_id);
