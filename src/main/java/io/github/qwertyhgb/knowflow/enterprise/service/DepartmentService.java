package io.github.qwertyhgb.knowflow.enterprise.service;

import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseDepartment;
import io.github.qwertyhgb.knowflow.enterprise.vo.DepartmentTreeVO;

import java.util.List;

/**
 * 企业部门业务服务。
 */
public interface DepartmentService {

    /**
     * 在指定企业中创建部门。
     *
     * <p>仅企业的正常 OWNER 或 ADMIN 成员可以创建部门。父部门不为空时，
     * 必须存在且属于同一企业；同一父部门下不允许出现重名部门。</p>
     *
     * <p>返回的实体用于业务层内部传递；后续接入 Controller 时应转换为响应 VO，
     * 不直接把持久化实体返回给客户端。</p>
     *
     * @param userId       当前登录用户 ID
     * @param enterpriseId 当前企业 ID
     * @param request      创建部门请求参数
     * @return 创建成功的部门实体
     */
    EnterpriseDepartment createDepartment(Long userId, Long enterpriseId, DepartmentCreateRequest request);

    /**
     * 查询指定企业的完整部门树。
     *
     * <p>企业的任意正常成员均可查询。结果包含正常和禁用部门，先按
     * {@code sortOrder}、再按部门 ID 稳定排序；无部门时返回空列表。</p>
     *
     * @param userId       当前登录用户 ID
     * @param enterpriseId 当前企业 ID
     * @return 一级部门节点列表，每个节点递归包含子部门
     */
    List<DepartmentTreeVO> listDepartmentTree(Long userId, Long enterpriseId);

    /**
     * 更新部门名称、父部门和排序值。
     *
     * <p>仅正常 OWNER/ADMIN 可操作；新父部门必须属于当前企业，且不能把部门
     * 移动到自身或自身的后代节点下。同一父节点下的部门名称仍须唯一。</p>
     *
     * @return 更新后的部门实体，供 Controller 转换为 VO
     */
    EnterpriseDepartment updateDepartment(Long userId, Long enterpriseId, Long departmentId,
                                          DepartmentUpdateRequest request);

    /**
     * 启用或禁用指定部门。
     *
     * <p>仅正常 OWNER/ADMIN 可操作；状态不向子部门级联。同状态请求按幂等处理，
     * 直接返回当前部门且不刷新更新时间。</p>
     *
     * @return 状态更新后的部门实体，供 Controller 转换为 VO
     */
    EnterpriseDepartment updateDepartmentStatus(Long userId, Long enterpriseId, Long departmentId,
                                                 DepartmentStatusUpdateRequest request);

    /**
     * 删除指定企业中的叶子部门。
     *
     * <p>仅正常 OWNER/ADMIN 可操作；存在直接子部门时拒绝删除，
     * 需要先移动或删除子部门。</p>
     */
    void deleteDepartment(Long userId, Long enterpriseId, Long departmentId);
}
