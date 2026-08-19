-- V16：创建操作日志表（audit_log）。
--
-- 设计意图：记录「用户做了什么」的关键业务操作（登录、创建/删除知识库、工单操作等）。
-- 由 {@code @OperationLog} 注解 + {@code OperationLogAspect} 切面统一写入——业务方法不感知。
--
-- 为什么操作日志落库而不是只打应用日志（stdout）：
--   - 应用日志是「排障」用的，输出到 stdout 由平台采集，事后再查很困难；
--   - 操作日志是「业务数据」：可查询（按用户/动作检索）、可审计（谁在何时做了什么）、
--     可统计（某动作发生频率）。数据库是可查询的唯一途径（本项目无本地日志文件）。
--   两者目的不同，不能混为一谈。
--
-- 为什么 user_id 可空：
--   - 认证接口（如登录失败）可能没有认证信息（principal 不存在），此时记录不了操作人，
--     用 NULL 表示「未登录操作」。这与字段「谁删的」的可追溯能力不冲突。
--
-- 为什么 success 用 TINYINT(1) 而非 BOOLEAN：
--   - MySQL 的 BOOLEAN 实际是 TINYINT(1) 的别名；H2（测试库，MODE=MySQL）对 BOOLEAN
--     的支持行为与 MySQL 有细微差异。用 TINYINT(1) 显式声明，一份脚本两端都最稳（双兼容规范）。
--
-- 为什么 error_message 只存「异常类名」而不存堆栈全文：
--   - 与日志白名单一致：异常 message 可能含用户输入（敏感）；堆栈很大。
--     只存异常类型名（如 BusinessException）即可定位「哪类失败」，详情由应用日志承载。
--
-- 为什么 params 列（存脱敏后的请求参数）：
--   - 审计要能追溯「这次操作带了什么参数」，参数名与关键值（如创建的知识库名/被分配的人）。
--     但直接序列化会把 password/token 等敏感字段落库——违反密码不落库的安全底线。
--     因此此处只存「脱敏后」的参数 JSON（敏感字段替换为 ***，超长截断 500 字符），
--     由切面在写入前处理（见 OperationLogAspect）。
--
-- 为什么只建 user_id / action 两个索引：
--   - 审计最常见查询是「某用户都做了什么」和「某动作频率」，两者各建普通索引即可；
--     不建过多索引，避免写入放大（审计是高频写）。
--
-- 已发布迁移禁止修改；本脚本按 Flyway 规范递增（V16）。

CREATE TABLE audit_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT
        /*! COMMENT '日志 ID' */,
    user_id       BIGINT       NULL
        /*! COMMENT '操作人用户 ID；未认证操作（如登录失败）为 NULL' */,
    action        VARCHAR(50)  NOT NULL
        /*! COMMENT '动作标识（机器可读、可检索、可统计），如 USER_LOGIN/ENTERPRISE_CREATE' */,
    description   VARCHAR(200) NOT NULL
        /*! COMMENT '人类可读描述，如「用户登录」' */,
    method        VARCHAR(200) NOT NULL
        /*! COMMENT '触发方法签名，如 io...UserController#login' */,
    success       TINYINT(1)   NOT NULL
        /*! COMMENT '是否成功：1=成功 / 0=失败' */,
    error_message VARCHAR(500) NULL
        /*! COMMENT '失败原因（仅异常类型名或固定短语，不含堆栈全文）' */,
    duration_ms   BIGINT       NOT NULL
        /*! COMMENT '耗时毫秒：慢操作排查的起点' */,
    ip            VARCHAR(45)  NULL
        /*! COMMENT '客户端 IP（IPv6 最长 45 字符）' */,
    params        VARCHAR(500) NULL
        /*! COMMENT '脱敏后的请求参数（敏感字段已替换为 ***，超长截断）' */,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,

    PRIMARY KEY (id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '操作日志表' */;

-- 按用户检索其操作记录（审计常见查询）。
CREATE INDEX idx_audit_log_user_id
    ON audit_log (user_id);

-- 按动作标识统计/检索（如某动作发生了多少次）。
CREATE INDEX idx_audit_log_action
    ON audit_log (action);