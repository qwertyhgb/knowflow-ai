-- V14:创建工单表 ticket。
--
-- 设计意图:工单是 Phase 13 的核心实体——客户提交问题工单,系统先用知识库
-- AI 自动回答,答不出转人工,客服分配处理,状态沿
-- OPEN → ASSIGNED → PROCESSING → RESOLVED → CLOSED 单向流转。
-- 本步(Phase 13 第一步)只落「创建 + AI 自动回答 + 我的列表/详情」,
-- assignee_id 列先建好但恒为 NULL,分配步骤使用。
--
-- 为什么工单是企业作用域资源(必须带 enterprise_id):
--   - 工单由企业成员提交、企业客服处理、知识库按企业隔离——AI 自动回答检索的
--     也是该企业的知识库。没有企业归属的工单无法确定「用哪家的知识库回答、
--     由哪家的客服处理」,多租户下数据也会互相串。
--
-- 为什么 description 用 LONGTEXT 而非 VARCHAR:
--   - 工单描述是客户自由输入的问题详情,可能包含完整的报错堆栈、操作步骤复述、
--     多轮沟通的截图说明,长度不可预估;VARCHAR 需要预设上限,设小了截断客户输入
--     (丢失关键排障信息),设大了浪费行外存储管理成本。LONGTEXT(4GB 上限)
--     一劳永逸,且应用层已用 @Size(max=5000) 做输入校验,列类型只做兜底。
--
-- 为什么 assignee_id 可空(NULL):
--   - 工单创建时尚未分配客服——「创建」与「分配」是两个独立动作(创建后先由
--     AI 尝试自动回答,客服再按优先级认领/被分配)。可空语义即「未分配」,
--     与状态机呼应:assignee_id 为 NULL 时状态恒为 OPEN。
--
-- 为什么不建外键(与项目既有约定一致):
--   - 企业/用户记录的存活性由各自模块管理,工单作为业务流水应保留历史;
--     外键会在成员退出/用户删除时制造约束冲突。归属校验在应用层完成。
--
-- 双兼容说明:MySQL 特有语法(ENGINE/CHARSET/COLLATE/COMMENT/ON UPDATE)
-- 用 /*! ... */ 包裹,MySQL 执行、H2 测试库忽略,一份脚本两端可跑。
-- 已执行的迁移脚本禁止修改;后续变更新增 V{n+1}。

CREATE TABLE ticket (
    id            BIGINT       NOT NULL AUTO_INCREMENT
        /*! COMMENT '工单 ID' */,
    enterprise_id BIGINT       NOT NULL
        /*! COMMENT '所属企业 ID(工单是企业作用域资源,成员校验与数据隔离的判据)' */,
    user_id       BIGINT       NOT NULL
        /*! COMMENT '提交人用户 ID(「我的工单」列表的查询维度)' */,
    category      VARCHAR(20)  NOT NULL
        /*! COMMENT '工单分类(CONSULTATION/ISSUE/FEEDBACK/OTHER,固定枚举保证可统计)' */,
    priority      VARCHAR(20)  NOT NULL
        /*! COMMENT '优先级(LOW/MEDIUM/HIGH/URGENT,后续分配排序的依据)' */,
    status        VARCHAR(20)  NOT NULL
        /*! COMMENT '状态(OPEN/ASSIGNED/PROCESSING/RESOLVED/CLOSED,单向流转,创建时恒为 OPEN)' */,
    title         VARCHAR(200) NOT NULL
        /*! COMMENT '工单标题(一句话概述问题,列表页展示)' */,
    description   LONGTEXT     NOT NULL
        /*! COMMENT '工单描述(客户提交的问题详情,长度不可预估故用 LONGTEXT;AI 自动回答以其为检索问题)' */,
    assignee_id   BIGINT       NULL
        /*! COMMENT '分配的客服用户 ID(创建时未分配恒为 NULL,分配步骤使用;NULL 语义即「未分配」)' */,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间(UTC)' */,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC):状态流转/回复时更新' */,

    PRIMARY KEY (id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '工单表:企业作用域资源,客户提交的问题工单(Phase 13)' */;

-- 企业维度查询:「某企业的全部工单」(后续客服视角列表的查询入口)。
CREATE INDEX idx_ticket_enterprise_id
    ON ticket (enterprise_id);

-- 用户维度查询:「我的工单」列表(本步的主查询路径,enterprise_id + user_id 组合过滤)。
CREATE INDEX idx_ticket_user_id
    ON ticket (user_id);
