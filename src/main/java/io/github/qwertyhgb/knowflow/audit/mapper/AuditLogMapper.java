package io.github.qwertyhgb.knowflow.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.qwertyhgb.knowflow.audit.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作日志 Mapper。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper} 获得 insert / select 等基础能力；
 * 切面写日志只用 insert（审计是「只写、可查询」，不涉及频繁更新/删除）。</p>
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}