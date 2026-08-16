package io.github.qwertyhgb.knowflow.enterprise.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRolePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 企业角色-权限关联表 Mapper。
 *
 * <p>除 BaseMapper 提供的基础 CRUD 外，额外提供一个自定义查询方法
 * {@link #selectPermissionCodesByRoleId(Long)}——本项目的第一个自定义 SQL，
 * 用于把角色拥有的权限码加载到认证上下文的 authorities，作为接口鉴权依据。</p>
 */
@Mapper
public interface EnterpriseRolePermissionMapper extends BaseMapper<EnterpriseRolePermission> {

    /**
     * 查询某企业角色被授予的全部权限码（如 {@code member:remove}）。
     *
     * <p><strong>为什么权限码能直接作为鉴权依据：</strong>权限码列表是接口鉴权
     * {@code @PreAuthorize("hasAuthority('member:remove')")} 的判断依据——
     * Spring Security 从 {@code Authentication.getAuthorities()} 匹配 authority，
     * 因此把权限码以 {@code SimpleGrantedAuthority} 形式写入认证上下文后，
     * 企业作用域内每个接口都能用声明式注解完成鉴权，无需在 Controller 手写判断。</p>
     *
     * <p>关联查询使用 {@code JOIN}：从关联表出发，按 {@code permission_id} 连到
     * 权限表取 {@code code}；若角色未关联任何权限，返回空列表，调用方按「无权限」处理。</p>
     *
     * @param roleId 企业角色 ID
     * @return 该角色被授予的权限码列表；无任何权限时返回空列表
     */
    @Select("SELECT p.code FROM enterprise_role_permission erp " +
            "JOIN permission p ON p.id = erp.permission_id " +
            "WHERE erp.enterprise_role_id = #{roleId}")
    List<String> selectPermissionCodesByRoleId(Long roleId);
}
