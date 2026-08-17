-- V9：创建文档表（document），完成知识库的核心资源载体。
--
-- 设计意图：文档是知识库内最核心的资源单元，是 Phase 6 的主体。
-- 一个文档归属于一个知识库，一个知识库包含多个文档。文档状态机设计如下：
--
--   UPLOADED  →  PARSING  →  PARSED  →  INDEXING  →  READY
--        ↓          ↓           ↓          ↓
--     FAILED     FAILED      FAILED      FAILED
--
-- 状态使用 VARCHAR 字符串枚举（存储可读英文单词）而非 TINYINT：
-- 一是日志和数据库直接查询时直观可读，无需映射表；
-- 二是方便扩展——后续新增状态不影响已有记录（参照 V4 enterprise_invitation.status 先例）。
-- 
-- 本脚本包含三步，按序执行：
--   1. 为 knowledge_base 补充 (enterprise_id, id) 复合候选键；
--   2. 创建 document 表，复合外键引用该候选键，防跨租户挂文档。
--
-- 已发布的迁移脚本禁止修改；后续结构变更请新增更高版本迁移。

-- 1. 为 knowledge_base 补充 (enterprise_id, id) 复合候选键。
--    参照 V5 enterprise_department、V6 enterprise_role 的既有模式，为知识库建立
--    (enterprise_id, id) 候选键，供文档表的复合外键引用，从数据库层面确保文档只能
--    挂到同一企业自身的知识库下，杜绝跨企业挂文档的越权操作。
ALTER TABLE knowledge_base
    ADD CONSTRAINT uk_knowledge_base_enterprise_id_id
        UNIQUE (enterprise_id, id);

-- 2. 文档表：知识库内的核心资源单元，附属于单个企业（租户）与单个知识库。
CREATE TABLE document (
    id                BIGINT       NOT NULL AUTO_INCREMENT
        /*! COMMENT '文档 ID' */,
    enterprise_id     BIGINT       NOT NULL
        /*! COMMENT '所属企业 ID（租户 ID）' */,
    knowledge_base_id BIGINT       NOT NULL
        /*! COMMENT '所属知识库 ID' */,
    uploader_user_id  BIGINT       NOT NULL
        /*! COMMENT '上传者用户 ID' */,
    file_name         VARCHAR(255) NOT NULL
        /*! COMMENT '原始文件名（含扩展名）' */,
    file_size         BIGINT       NOT NULL
        /*! COMMENT '文件大小（字节）' */,
    content_type      VARCHAR(100) NULL
        /*! COMMENT 'MIME 类型' */,
    file_hash         CHAR(64)     NOT NULL
        /*! COMMENT '文件内容 SHA-256 十六进制（重复文件检测依据）' */,
    storage_key       VARCHAR(255) NOT NULL
        /*! COMMENT '本地存储相对路径（含随机文件名与扩展名），存储层唯一' */,
    status            VARCHAR(16)  NOT NULL DEFAULT 'UPLOADED'
        /*! COMMENT '文档状态：UPLOADED-已上传，PARSING-解析中，PARSED-已解析，INDEXING-索引中，READY-就绪，FAILED-失败' */,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）' */,

    PRIMARY KEY (id),
    -- 文档所属企业必须是真实存在的企业。
    CONSTRAINT fk_document_enterprise
        FOREIGN KEY (enterprise_id) REFERENCES enterprise (id),
    -- 复合外键 (enterprise_id, knowledge_base_id) 引用 knowledge_base 的候选键
    -- (enterprise_id, id)：文档只能挂到同一企业自身的知识库下，从数据层杜绝
    -- 跨企业挂文档（参照 V5/V6/V8 的复合外键防跨租户模式）。
    CONSTRAINT fk_document_knowledge_base
        FOREIGN KEY (enterprise_id, knowledge_base_id)
        REFERENCES knowledge_base (enterprise_id, id),
    -- 上传者必须是真实存在的平台用户。
    CONSTRAINT fk_document_uploader_user
        FOREIGN KEY (uploader_user_id) REFERENCES sys_user (id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '文档表' */;

-- 按知识库查询文档列表（所属知识库 + 状态过滤）：知识库内的文档列表页。
CREATE INDEX idx_document_kb_status
    ON document (knowledge_base_id, status);

-- 按企业 + 文件哈希值查询，用于重复文件检测：同一企业内不允许上传相同内容的文档。
CREATE INDEX idx_document_enterprise_hash
    ON document (enterprise_id, file_hash);