-- V7：退役 enterprise_member.member_role 列（两阶段迁移第二步）。
--
-- 背景：
--   V3 发布时成员角色以字符串列 member_role 存储（'OWNER'/'ADMIN'/'MEMBER'）；
--   V6 引入企业角色表 enterprise_role 与成员关联列 role_id（两阶段迁移第一步），
--   并通过回填把既有成员的 member_role 映射为对应内置角色的 role_id；
--   本仓库 Phase 4 收尾已把业务代码全部切换为以 role_id 为准
--   （EnterpriseMember 实体删除 memberRole 字段，角色判断改经 enterprise_role.code），
--   此时删除 V3 遗留的 member_role 列，完成两阶段迁移。
--
-- 注意：enterprise_invitation.member_role 列（V4）不在此列，邀请时仍以字符串角色
-- 指定授予角色，接受邀请时再映射为目标企业的角色 ID。
--
-- 已发布的迁移脚本禁止修改；后续结构变更请新增更高版本迁移。

ALTER TABLE enterprise_member DROP COLUMN member_role;
