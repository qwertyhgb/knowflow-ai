package io.github.qwertyhgb.knowflow.enterprise.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseDepartment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 企业部门表的持久化访问接口，只处理 {@link EnterpriseDepartment} 实体。
 *
 * <p>当前不编写自定义 SQL，使用 {@link BaseMapper} 提供的基础 CRUD。</p>
 */
@Mapper
public interface EnterpriseDepartmentMapper extends BaseMapper<EnterpriseDepartment> {
}
