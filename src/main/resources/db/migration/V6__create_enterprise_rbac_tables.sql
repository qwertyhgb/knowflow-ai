-- V6：创建企业 RBAC 权限体系表（enterprise_role / permission / enterprise_role_permission）。
--
-- 本版本引入企业级 RBAC 的第一批存储结构：
--   - enterprise_role            企业角色表：角色是企业级资源，归属于单个企业（租户）。
--   - permission                 平台级权限表：与租户无关的全局权限清单。
--   - enterprise_role_permission 角色-权限多对多关联表。
--   - enterprise_member.role_id  成员与角色的关联（两阶段迁移的第一步）。
--
-- 关于两阶段迁移：
--   成员角色字段 member_role 已在 V3 发布，保存 'OWNER'/'ADMIN'/'MEMBER' 字符串；
--   本版本先通过回填把既有成员关联到对应内置角色（role_id），随后补充复合外键与
--   非空约束，保留 member_role 不动，避免破坏已发布脚本与既有数据。业务侧后续再
--   以 role_id 为准，member_role 仅作为历史兼容字段保留。
--
-- 复合外键 fk_enterprise_member_role(enterprise_id, role_id) 引用
--   enterprise_role(enterprise_id, id)，且 enterprise_role 提供
--   uk_enterprise_role_enterprise_id_id 复合唯一键作为外键引用目标：
--   防止成员把角色挂到其他企业的角色上（跨租户越权）。
--
-- 已发布的迁移脚本禁止修改；后续结构变更请新增更高版本迁移。

-- 1. 企业角色表：角色是企业级资源，与所属企业强绑定。
CREATE TABLE enterprise_role (
    id            BIGINT       NOT NULL AUTO_INCREMENT
        /*! COMMENT '企业角色 ID' */,
    enterprise_id BIGINT       NOT NULL
        /*! COMMENT '所属企业 ID（租户 ID）' */,
    code          VARCHAR(32)  NOT NULL
        /*! COMMENT '角色编码，企业内部唯一：OWNER、ADMIN、MEMBER' */,
    name          VARCHAR(50)  NOT NULL
        /*! COMMENT '角色名称' */,
    description   VARCHAR(255) NULL
        /*! COMMENT '角色描述' */,
    status        TINYINT      NOT NULL DEFAULT 1
        /*! COMMENT '角色状态：0-禁用，1-正常' */,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）' */,

    PRIMARY KEY (id),
    -- 同一企业内角色编码唯一。
    CONSTRAINT uk_enterprise_role_enterprise_code
        UNIQUE (enterprise_id, code),
    -- 复合候选键：供企业成员复合外键 fk_enterprise_member_role 引用，防止跨企业挂角色。
    CONSTRAINT uk_enterprise_role_enterprise_id_id
        UNIQUE (enterprise_id, id),
    CONSTRAINT fk_enterprise_role_enterprise
        FOREIGN KEY (enterprise_id) REFERENCES enterprise (id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '企业角色表' */;

-- 2. 平台级权限表：全局权限清单，与租户无关。
CREATE TABLE permission (
    id         BIGINT       NOT NULL AUTO_INCREMENT
        /*! COMMENT '权限 ID' */,
    code       VARCHAR(100) NOT NULL
        /*! COMMENT '权限编码，平台全局唯一，如 enterprise:update' */,
    name       VARCHAR(50)  NOT NULL
        /*! COMMENT '权限名称' */,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）' */,

    PRIMARY KEY (id),
    CONSTRAINT uk_permission_code
        UNIQUE (code)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '平台级权限表' */;

-- 3. 企业角色-权限多对多关联表。
CREATE TABLE enterprise_role_permission (
    id                  BIGINT      NOT NULL AUTO_INCREMENT
        /*! COMMENT '角色权限关联 ID' */,
    enterprise_role_id  BIGINT      NOT NULL
        /*! COMMENT '企业角色 ID' */,
    permission_id       BIGINT      NOT NULL
        /*! COMMENT '权限 ID' */,
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,

    PRIMARY KEY (id),
    -- 同一角色对同一权限只记录一次。
    CONSTRAINT uk_enterprise_role_permission_role_permission
        UNIQUE (enterprise_role_id, permission_id),
    CONSTRAINT fk_enterprise_role_permission_role
        FOREIGN KEY (enterprise_role_id) REFERENCES enterprise_role (id),
    CONSTRAINT fk_enterprise_role_permission_permission
        FOREIGN KEY (permission_id) REFERENCES permission (id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '企业角色-权限关联表' */;

-- 4. enterprise_member 两阶段迁移第一步（保留 member_role 列不动）。
--    a. 先新增可空的 role_id，关联到企业内置角色。
ALTER TABLE enterprise_member
    ADD COLUMN role_id BIGINT NULL
        /*! COMMENT '成员关联的企业角色 ID' */;

--    b. 为每个已有企业插入 3 个内置角色（OWNER / ADMIN / MEMBER）。
--       角色编码与名称首期约定，后续如需更多角色由业务层在对应企业下创建。
INSERT INTO enterprise_role (enterprise_id, code, name, description, created_at, updated_at)
SELECT e.id, 'OWNER', '所有者', '企业所有者，拥有全部权限', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM enterprise e;

INSERT INTO enterprise_role (enterprise_id, code, name, description, created_at, updated_at)
SELECT e.id, 'ADMIN', '管理员', '可管理成员与大部分企业设置', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM enterprise e;

INSERT INTO enterprise_role (enterprise_id, code, name, description, created_at, updated_at)
SELECT e.id, 'MEMBER', '成员', '默认角色', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM enterprise e;

--    c. 回填：按成员既有的 member_role 编码，把每个成员关联到所属企业的对应内置角色。
--       使用相关子查询而非 UPDATE...JOIN，保证 MySQL 与 H2（MODE=MySQL）都能执行。
UPDATE enterprise_member m
SET m.role_id = (
    SELECT r.id
    FROM enterprise_role r
    WHERE r.enterprise_id = m.enterprise_id
      AND r.code = m.member_role
);

--    d. 补充复合外键：成员引用的角色必须属于同一企业，防止跨企业越权挂角色。
ALTER TABLE enterprise_member
    ADD CONSTRAINT fk_enterprise_member_role
        FOREIGN KEY (enterprise_id, role_id)
        REFERENCES enterprise_role (enterprise_id, id);

--    e. 改为非空：H2 不支持 MODIFY COLUMN，故仅 MySQL 通过可执行注释执行。
--       回填已在上一步完成，此时收紧为 NOT NULL，保证每个成员必然关联一个角色。
/*! ALTER TABLE enterprise_member MODIFY COLUMN role_id BIGINT NOT NULL COMMENT '成员关联的企业角色 ID' */;

-- 5. 预置平台级权限数据（一次插入全部；后续新增权限用新版本迁移补充）。
INSERT INTO permission (code, name, created_at, updated_at) VALUES
    ('enterprise:update', '更新企业资料', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('member:view', '查看成员列表', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('member:remove', '移除成员', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('member:status', '修改成员状态', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('invitation:create', '创建邀请', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('invitation:list', '查看邀请列表', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('invitation:revoke', '撤销邀请', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('department:create', '创建部门', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('department:update', '更新部门', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('department:delete', '删除部门', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('department:status', '修改部门状态', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

-- 6. 角色-权限关联：OWNER 与 ADMIN 当前都关联全部 11 个权限（两者权限集相同，
--    差异靠业务层规则区分）；MEMBER 默认不关联任何权限。
--    member:leave、部门树查看等"成员基础能力"不建权限码，只要求成员身份，由业务层判断。
INSERT INTO enterprise_role_permission (enterprise_role_id, permission_id, created_at)
SELECT r.id, p.id, CURRENT_TIMESTAMP(3)
FROM enterprise_role r
CROSS JOIN permission p
WHERE r.code IN ('OWNER', 'ADMIN');
