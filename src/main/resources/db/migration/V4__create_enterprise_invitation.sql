-- V4：创建企业成员邀请表 enterprise_invitation。
--
-- 邀请以邮箱为接收目标；当前阶段只保存邀请数据，暂不负责邮件发送。
-- token_hash 只保存邀请令牌的 SHA-256 哈希值，禁止保存可直接使用的明文令牌。
-- 邀请角色首期只允许业务层写入 ADMIN、MEMBER，OWNER 不通过邀请直接授予。
-- 邀请状态使用可读字符串，首期约定 PENDING、ACCEPTED、REVOKED、EXPIRED。
-- 已发布的迁移脚本禁止修改；后续结构变更请新增更高版本迁移。

CREATE TABLE enterprise_invitation (
    id                   BIGINT       NOT NULL AUTO_INCREMENT
        /*! COMMENT '企业邀请 ID' */,
    enterprise_id        BIGINT       NOT NULL
        /*! COMMENT '目标企业 ID（租户 ID）' */,
    inviter_user_id      BIGINT       NOT NULL
        /*! COMMENT '发起邀请的用户 ID' */,
    invitee_email        VARCHAR(254) NOT NULL
        /*! COMMENT '被邀请人的规范化邮箱（小写）' */,
    member_role          VARCHAR(32)  NOT NULL DEFAULT 'MEMBER'
        /*! COMMENT '加入后的成员角色：ADMIN、MEMBER' */,
    token_hash           CHAR(64)     NOT NULL
        /*! COMMENT '邀请令牌的 SHA-256 哈希值（十六进制）' */,
    status               VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        /*! COMMENT '邀请状态：PENDING、ACCEPTED、REVOKED、EXPIRED' */,
    expires_at           DATETIME(3)  NOT NULL
        /*! COMMENT '邀请过期时间（UTC）' */,
    accepted_by_user_id  BIGINT       NULL
        /*! COMMENT '实际接受邀请的用户 ID' */,
    accepted_at          DATETIME(3)  NULL
        /*! COMMENT '接受邀请时间（UTC）' */,
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,
    updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）' */,

    PRIMARY KEY (id),
    CONSTRAINT uk_enterprise_invitation_token_hash
        UNIQUE (token_hash),
    CONSTRAINT fk_enterprise_invitation_enterprise
        FOREIGN KEY (enterprise_id) REFERENCES enterprise (id),
    CONSTRAINT fk_enterprise_invitation_inviter
        FOREIGN KEY (inviter_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_enterprise_invitation_accepted_user
        FOREIGN KEY (accepted_by_user_id) REFERENCES sys_user (id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '企业成员邀请表' */;

-- 查询企业的邀请记录，例如邀请管理列表和待处理邀请检查。
CREATE INDEX idx_enterprise_invitation_enterprise_status
    ON enterprise_invitation (enterprise_id, status, created_at);

-- 查询某个邮箱的待处理邀请；同企业同邮箱的重复待处理邀请由业务层控制。
CREATE INDEX idx_enterprise_invitation_email_status
    ON enterprise_invitation (invitee_email, status);

-- 扫描并处理已经到期但仍处于 PENDING 状态的邀请。
CREATE INDEX idx_enterprise_invitation_status_expires
    ON enterprise_invitation (status, expires_at);
