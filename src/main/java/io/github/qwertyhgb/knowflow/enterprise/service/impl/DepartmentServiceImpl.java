package io.github.qwertyhgb.knowflow.enterprise.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseDepartment;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseDepartmentStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseDepartmentMapper;
import io.github.qwertyhgb.knowflow.enterprise.service.DepartmentService;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseMembershipChecker;
import io.github.qwertyhgb.knowflow.enterprise.vo.DepartmentTreeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 企业部门业务服务实现。
 *
 * <p><strong>创建流程：</strong>校验企业存在与管理成员权限，校验父部门的企业归属，
 * 检查同级部门名称是否重复，最后以 NORMAL 状态和统一 UTC 时间写入部门记录。</p>
 */
@Slf4j
@Service
public class DepartmentServiceImpl implements DepartmentService {

    private final EnterpriseDepartmentMapper enterpriseDepartmentMapper;

    /** 企业成员身份校验（企业存在 → 404；正常成员 → 403），与成员/邀请模块共用。 */
    private final EnterpriseMembershipChecker membershipChecker;

    /** 可注入的 UTC 时钟，便于测试冻结创建时间。 */
    private final Clock clock;

    public DepartmentServiceImpl(EnterpriseDepartmentMapper enterpriseDepartmentMapper,
                                 EnterpriseMembershipChecker membershipChecker,
                                 Clock clock) {
        this.enterpriseDepartmentMapper = enterpriseDepartmentMapper;
        this.membershipChecker = membershipChecker;
        this.clock = clock;
    }

    @Override
    @Transactional
    public EnterpriseDepartment createDepartment(Long userId, Long enterpriseId,
                                                 DepartmentCreateRequest request) {
        // 1. 先校验企业存在，再校验成员身份，保持与现有企业业务一致的错误顺序。
        //    创建部门的权限（department:create）由 Controller 的 @PreAuthorize 校验，
        //    此处只保证企业成员身份，不再判断 OWNER/ADMIN 角色。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 2. 父部门必须属于当前企业；组合条件可以阻止把部门挂到其他租户的部门下。
        Long parentId = request.getParentId();
        if (parentId != null) {
            EnterpriseDepartment parent = enterpriseDepartmentMapper.selectOne(
                    new LambdaQueryWrapper<EnterpriseDepartment>()
                            .eq(EnterpriseDepartment::getId, parentId)
                            .eq(EnterpriseDepartment::getEnterpriseId, enterpriseId));
            if (parent == null) {
                throw new BusinessException(ErrorCode.DEPARTMENT_PARENT_NOT_FOUND);
            }
        }

        // 3. 名称入库前去除首尾空白；同一父级下不允许重名。
        String name = request.getName().strip();
        LambdaQueryWrapper<EnterpriseDepartment> duplicateQuery =
                new LambdaQueryWrapper<EnterpriseDepartment>()
                        .eq(EnterpriseDepartment::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseDepartment::getName, name);
        if (parentId == null) {
            duplicateQuery.isNull(EnterpriseDepartment::getParentId);
        } else {
            duplicateQuery.eq(EnterpriseDepartment::getParentId, parentId);
        }
        if (enterpriseDepartmentMapper.exists(duplicateQuery)) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NAME_ALREADY_EXISTS);
        }

        // 4. 状态和时间由后端统一赋值，避免客户端绕过创建规则。
        Instant now = clock.instant();
        EnterpriseDepartment department = new EnterpriseDepartment();
        department.setEnterpriseId(enterpriseId);
        department.setParentId(parentId);
        department.setName(name);
        department.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        department.setStatus(EnterpriseDepartmentStatus.NORMAL);
        department.setCreatedAt(now);
        department.setUpdatedAt(now);
        enterpriseDepartmentMapper.insert(department);

        // 只记录系统标识，不记录用户提交的部门名称。
        log.info("event=department_created enterpriseId={} departmentId={} operatorId={}",
                enterpriseId, department.getId(), userId);
        return department;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentTreeVO> listDepartmentTree(Long userId, Long enterpriseId) {
        // 1. 与其他企业作用域查询一致：先判断企业是否存在，再校验正常成员身份。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 2. 一次查询取回当前企业的全部部门，避免递归查询造成 N+1。
        //    全局按 sortOrder + id 排序后，各父节点下的子列表也会保持相同稳定顺序。
        List<EnterpriseDepartment> departments = enterpriseDepartmentMapper.selectList(
                new LambdaQueryWrapper<EnterpriseDepartment>()
                        .eq(EnterpriseDepartment::getEnterpriseId, enterpriseId)
                        .orderByAsc(EnterpriseDepartment::getSortOrder)
                        .orderByAsc(EnterpriseDepartment::getId));
        if (departments.isEmpty()) {
            return List.of();
        }

        // 3. 先按 parentId 建立邻接表，再从一级部门递归组装响应树。
        //    V5 的复合外键保证父子部门属于同一企业，因此无需跨租户补查。
        List<EnterpriseDepartment> roots = new ArrayList<>();
        Map<Long, List<EnterpriseDepartment>> childrenByParentId = new HashMap<>();
        for (EnterpriseDepartment department : departments) {
            if (department.getParentId() == null) {
                roots.add(department);
            } else {
                childrenByParentId
                        .computeIfAbsent(department.getParentId(), ignored -> new ArrayList<>())
                        .add(department);
            }
        }
        return roots.stream()
                .map(root -> toTreeNode(root, childrenByParentId))
                .toList();
    }

    @Override
    @Transactional
    public EnterpriseDepartment updateDepartment(Long userId, Long enterpriseId, Long departmentId,
                                                 DepartmentUpdateRequest request) {
        // 1. 更新属于管理操作：企业必须存在，操作者必须是正常成员。
        //    更新部门的权限（department:update）由 Controller 的 @PreAuthorize 校验。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 2. 一次加载当前企业部门，用于定位目标、验证父部门归属和检测层级循环。
        List<EnterpriseDepartment> departments = enterpriseDepartmentMapper.selectList(
                new LambdaQueryWrapper<EnterpriseDepartment>()
                        .eq(EnterpriseDepartment::getEnterpriseId, enterpriseId));
        Map<Long, EnterpriseDepartment> departmentById = new HashMap<>();
        for (EnterpriseDepartment department : departments) {
            departmentById.put(department.getId(), department);
        }
        EnterpriseDepartment target = departmentById.get(departmentId);
        if (target == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }

        // 3. parentId 为空表示移动到根层级；非空时必须属于当前企业且不能形成环。
        Long parentId = request.getParentId();
        if (parentId != null) {
            if (!departmentById.containsKey(parentId)) {
                throw new BusinessException(ErrorCode.DEPARTMENT_PARENT_NOT_FOUND);
            }
            requireAcyclicParent(departmentId, parentId, departmentById);
        }

        // 4. 复用创建时的同级名称唯一规则，并排除当前部门自身。
        String name = request.getName().strip();
        LambdaQueryWrapper<EnterpriseDepartment> duplicateQuery =
                new LambdaQueryWrapper<EnterpriseDepartment>()
                        .eq(EnterpriseDepartment::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseDepartment::getName, name)
                        .ne(EnterpriseDepartment::getId, departmentId);
        if (parentId == null) {
            duplicateQuery.isNull(EnterpriseDepartment::getParentId);
        } else {
            duplicateQuery.eq(EnterpriseDepartment::getParentId, parentId);
        }
        if (enterpriseDepartmentMapper.exists(duplicateQuery)) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NAME_ALREADY_EXISTS);
        }

        // 5. 使用 UpdateWrapper 显式 SET parent_id，确保移动为一级部门时能真正写入 NULL；
        //    updateById 默认忽略 null 字段，不能满足该场景。
        Integer sortOrder = request.getSortOrder() == null ? 0 : request.getSortOrder();
        Instant updatedAt = clock.instant();
        int updatedCount = enterpriseDepartmentMapper.update(null,
                new LambdaUpdateWrapper<EnterpriseDepartment>()
                        .eq(EnterpriseDepartment::getId, departmentId)
                        .eq(EnterpriseDepartment::getEnterpriseId, enterpriseId)
                        .set(EnterpriseDepartment::getName, name)
                        .set(EnterpriseDepartment::getParentId, parentId)
                        .set(EnterpriseDepartment::getSortOrder, sortOrder)
                        .set(EnterpriseDepartment::getUpdatedAt, updatedAt));
        if (updatedCount != 1) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }

        target.setName(name);
        target.setParentId(parentId);
        target.setSortOrder(sortOrder);
        target.setUpdatedAt(updatedAt);
        log.info("event=department_updated enterpriseId={} departmentId={} operatorId={}",
                enterpriseId, departmentId, userId);
        return target;
    }

    @Override
    @Transactional
    public EnterpriseDepartment updateDepartmentStatus(
            Long userId, Long enterpriseId, Long departmentId,
            DepartmentStatusUpdateRequest request) {
        // 1. 状态变更属于管理操作，沿用部门更新与删除的成员身份校验。
        //    修改部门状态的权限（department:status）由 Controller 的 @PreAuthorize 校验。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 2. 使用「企业 + 部门 ID」定位目标，防止跨企业修改部门状态。
        EnterpriseDepartment target = enterpriseDepartmentMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseDepartment>()
                        .eq(EnterpriseDepartment::getId, departmentId)
                        .eq(EnterpriseDepartment::getEnterpriseId, enterpriseId));
        if (target == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }

        // 3. 同状态请求幂等返回，不产生无意义的数据库写入和更新时间变化。
        EnterpriseDepartmentStatus targetStatus = request.getStatus();
        if (target.getStatus() == targetStatus) {
            return target;
        }

        // 4. 只更新目标部门自身；子部门状态保持不变，由管理员显式维护。
        Instant updatedAt = clock.instant();
        int updatedCount = enterpriseDepartmentMapper.update(null,
                new LambdaUpdateWrapper<EnterpriseDepartment>()
                        .eq(EnterpriseDepartment::getId, departmentId)
                        .eq(EnterpriseDepartment::getEnterpriseId, enterpriseId)
                        .set(EnterpriseDepartment::getStatus, targetStatus)
                        .set(EnterpriseDepartment::getUpdatedAt, updatedAt));
        if (updatedCount != 1) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }

        target.setStatus(targetStatus);
        target.setUpdatedAt(updatedAt);
        log.info("event=department_status_updated enterpriseId={} departmentId={} operatorId={} newStatus={}",
                enterpriseId, departmentId, userId, targetStatus);
        return target;
    }

    @Override
    @Transactional
    public void deleteDepartment(Long userId, Long enterpriseId, Long departmentId) {
        // 1. 删除属于管理操作，并保持先资源、后权限的校验顺序。
        //    删除部门的权限（department:delete）由 Controller 的 @PreAuthorize 校验。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 2. 必须按「企业 + 部门 ID」定位，避免通过其他企业的 ID 操作跨租户数据。
        EnterpriseDepartment target = enterpriseDepartmentMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseDepartment>()
                        .eq(EnterpriseDepartment::getId, departmentId)
                        .eq(EnterpriseDepartment::getEnterpriseId, enterpriseId));
        if (target == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }

        // 3. 只允许删除叶子部门；同时给数据库自关联外键提供清晰的业务错误响应。
        boolean hasChildren = enterpriseDepartmentMapper.exists(
                new LambdaQueryWrapper<EnterpriseDepartment>()
                        .eq(EnterpriseDepartment::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseDepartment::getParentId, departmentId));
        if (hasChildren) {
            throw new BusinessException(ErrorCode.DEPARTMENT_HAS_CHILDREN);
        }

        int deletedCount = enterpriseDepartmentMapper.delete(
                new LambdaQueryWrapper<EnterpriseDepartment>()
                        .eq(EnterpriseDepartment::getId, departmentId)
                        .eq(EnterpriseDepartment::getEnterpriseId, enterpriseId));
        if (deletedCount != 1) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }
        log.info("event=department_deleted enterpriseId={} departmentId={} operatorId={}",
                enterpriseId, departmentId, userId);
    }

    /** 沿新父部门的祖先链向上检查，防止移动到自己或自己的后代节点下。 */
    private void requireAcyclicParent(Long departmentId, Long parentId,
                                      Map<Long, EnterpriseDepartment> departmentById) {
        Set<Long> visited = new HashSet<>();
        Long currentId = parentId;
        while (currentId != null) {
            if (departmentId.equals(currentId) || !visited.add(currentId)) {
                throw new BusinessException(ErrorCode.DEPARTMENT_PARENT_CYCLE);
            }
            EnterpriseDepartment current = departmentById.get(currentId);
            currentId = current == null ? null : current.getParentId();
        }
    }

    /** 递归组装一个部门节点；叶子部门得到不可变空列表。 */
    private DepartmentTreeVO toTreeNode(
            EnterpriseDepartment department,
            Map<Long, List<EnterpriseDepartment>> childrenByParentId) {
        List<DepartmentTreeVO> children = childrenByParentId
                .getOrDefault(department.getId(), List.of())
                .stream()
                .map(child -> toTreeNode(child, childrenByParentId))
                .toList();
        return DepartmentTreeVO.from(department, children);
    }

}
