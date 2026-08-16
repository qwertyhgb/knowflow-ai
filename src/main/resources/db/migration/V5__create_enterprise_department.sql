-- V5：创建企业部门表 enterprise_department。
--
-- 部门属于单个企业（租户），通过 parent_id 构成简单的父子层级：
-- parent_id 为 NULL 表示一级部门，非 NULL 表示上级部门。
-- 复合自关联外键同时包含 enterprise_id，避免部门错误挂到其他企业的父部门下。
-- 同一父部门下的名称重复与部门层级循环由业务层校验，当前不引入复杂数据库表达式。
-- 已发布的迁移脚本禁止修改；后续结构变更请新增更高版本迁移。

CREATE TABLE enterprise_department (
    id            BIGINT       NOT NULL AUTO_INCREMENT
        /*! COMMENT '部门 ID' */,
    enterprise_id BIGINT       NOT NULL
        /*! COMMENT '所属企业 ID（租户 ID）' */,
    parent_id     BIGINT       NULL
        /*! COMMENT '上级部门 ID；NULL 表示一级部门' */,
    name          VARCHAR(100) NOT NULL
        /*! COMMENT '部门名称' */,
    sort_order    INT          NOT NULL DEFAULT 0
        /*! COMMENT '同级部门排序值，数值越小越靠前' */,
    status        TINYINT      NOT NULL DEFAULT 1
        /*! COMMENT '部门状态：0-禁用，1-正常' */,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）' */,

    PRIMARY KEY (id),
    -- 为复合自关联外键提供唯一候选键，确保父子部门属于同一企业。
    CONSTRAINT uk_enterprise_department_enterprise_id_id
        UNIQUE (enterprise_id, id),
    CONSTRAINT fk_enterprise_department_enterprise
        FOREIGN KEY (enterprise_id) REFERENCES enterprise (id),
    CONSTRAINT fk_enterprise_department_parent
        FOREIGN KEY (enterprise_id, parent_id)
        REFERENCES enterprise_department (enterprise_id, id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '企业部门表' */;

-- 查询某个企业下指定父部门的子部门，并按状态、排序值稳定排序。
CREATE INDEX idx_enterprise_department_enterprise_parent
    ON enterprise_department (enterprise_id, parent_id, status, sort_order, id);
