-- V8：创建知识库资源表（knowledge_base / knowledge_base_member）。
--
-- 设计意图：知识库是企业内的「资源级」数据单元。V6 引入的企业级 RBAC 把权限
-- 粒度收敛到「企业」这一层（成员在企业内拥有的角色与权限相同）；
-- 本版本在此基础上向前推进一步：把权限从「企业级」细化到「资源级」——
-- 知识库由企业成员创建，同一个企业内不同成员可以拥有对不同知识库的不同权限，
-- 这一粒度由 knowledge_base_member（知识库成员表）承载。
--
-- 核心安全模型沿用了 V5（enterprise_department）与 V6（enterprise_role）的
-- 「冗余 enterprise_id + 复合外键」约定：
--   - enterprise_member 具备候选键 (enterprise_id, user_id)，标定「某用户在某企业内是成员」；
--   - knowledge_base_member 用复合外键 (enterprise_id, user_id) 引用该候选键，
--     从数据库层面杜绝「把某个用户挂成其他企业知识库成员」的跨租户越权。
-- 冗余的 enterprise_id 同时避免了每次权限判断都要回表 join enterprise_member，
-- 利于按企业维度直接查询与计数。
--
-- 已发布的迁移脚本禁止修改；后续结构变更请新增更高版本迁移。

-- 1. 知识库表：资源级数据单元，归属于单个企业（租户）。
CREATE TABLE knowledge_base (
    id            BIGINT       NOT NULL AUTO_INCREMENT
        /*! COMMENT '知识库 ID' */,
    enterprise_id BIGINT       NOT NULL
        /*! COMMENT '所属企业 ID（租户 ID）' */,
    name          VARCHAR(100) NOT NULL
        /*! COMMENT '知识库名称' */,
    description   VARCHAR(500) NULL
        /*! COMMENT '知识库描述' */,
    access_mode   VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE'
        /*! COMMENT '访问模式：PRIVATE-仅知识库成员可见，PUBLIC-企业内所有正常成员可见' */,
    owner_user_id BIGINT       NOT NULL
        /*! COMMENT '创建者用户 ID（企业成员）' */,
    status        TINYINT      NOT NULL DEFAULT 1
        /*! COMMENT '知识库状态：0-禁用，1-正常' */,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）' */,

    PRIMARY KEY (id),
    -- 知识库名称在企业内不强制唯一：同名知识库靠 ID 区分，业务层设计上不查重。
    -- 这是明确的设计决策——知识库借助唯一路径/链接访问，允许两个同名但内容不同的库并存。
    CONSTRAINT fk_knowledge_base_enterprise
        FOREIGN KEY (enterprise_id) REFERENCES enterprise (id),
    -- 创建者必须是真实存在的平台用户；其是否为企业成员由知识库成员/业务层校验。
    CONSTRAINT fk_knowledge_base_owner_user
        FOREIGN KEY (owner_user_id) REFERENCES sys_user (id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '知识库表' */;

-- 企业维度、按状态列出知识库（企业内的知识库列表查询）。
CREATE INDEX idx_knowledge_base_enterprise_status
    ON knowledge_base (enterprise_id, status);

-- 2. 知识库成员表：资源级权限的核心，标定「哪个用户拥有哪个知识库的何种权限」。
CREATE TABLE knowledge_base_member (
    id                 BIGINT      NOT NULL AUTO_INCREMENT
        /*! COMMENT '知识库成员关系 ID' */,
    knowledge_base_id  BIGINT      NOT NULL
        /*! COMMENT '知识库 ID' */,
    enterprise_id      BIGINT      NOT NULL
        /*! COMMENT '冗余企业 ID，用于复合外键防跨租户挂成员' */,
    user_id            BIGINT      NOT NULL
        /*! COMMENT '用户 ID' */,
    member_role        VARCHAR(16) NOT NULL DEFAULT 'VIEWER'
        /*! COMMENT '成员角色：VIEWER-只读，EDITOR-可编辑，ADMIN-管理' */,
    created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,
    updated_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）' */,

    PRIMARY KEY (id),
    -- 同一知识库内一个用户只能有一条成员记录（角色通过更新而非新增来调整）。
    CONSTRAINT uk_knowledge_base_member_kb_user
        UNIQUE (knowledge_base_id, user_id),
    CONSTRAINT fk_knowledge_base_member_kb
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    -- 复合外键 (enterprise_id, user_id) 引用 enterprise_member 的候选键
    -- (enterprise_id, user_id)：知识库成员必须先是在对应企业内的正常成员，
    -- 且只能挂到该企业自己的知识库，从数据层杜绝跨租户挂成员（沿用 V5/V6 模式）。
    CONSTRAINT fk_knowledge_base_member_enterprise_member
        FOREIGN KEY (enterprise_id, user_id)
        REFERENCES enterprise_member (enterprise_id, user_id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '知识库成员表' */;

-- 「我参与的知识库」查询：按用户维度反查其拥有的知识库成员关系。
CREATE INDEX idx_knowledge_base_member_user
    ON knowledge_base_member (user_id);
