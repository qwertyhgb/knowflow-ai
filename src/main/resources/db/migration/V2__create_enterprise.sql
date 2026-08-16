-- V2：创建企业表 enterprise。
--
-- Enterprise 是多租户系统中的租户（Workspace）边界。
-- slug 是面向 URL、接口参数和日志的稳定业务标识，企业名称允许后续修改。
-- 已发布的迁移脚本禁止修改；后续结构变更请新增更高版本迁移。

CREATE TABLE enterprise (
    id         BIGINT       NOT NULL AUTO_INCREMENT
        /*! COMMENT '企业 ID（租户 ID）' */,
    name       VARCHAR(100) NOT NULL
        /*! COMMENT '企业名称' */,
    slug       VARCHAR(64)  NOT NULL
        /*! COMMENT '企业唯一标识，用于 URL 和接口参数' */,
    status     TINYINT      NOT NULL DEFAULT 1
        /*! COMMENT '企业状态：0-禁用，1-正常' */,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）' */,

    PRIMARY KEY (id),
    CONSTRAINT uk_enterprise_slug UNIQUE (slug)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '企业（租户）表' */;

CREATE INDEX idx_enterprise_status ON enterprise (status);
