package io.github.qwertyhgb.knowflow.enterprise.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.qwertyhgb.knowflow.enterprise.entity.Permission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 平台级权限表 Mapper，暂不编写自定义 SQL，使用 BaseMapper 提供的基础 CRUD。
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {
}
