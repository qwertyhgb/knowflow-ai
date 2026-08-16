package io.github.qwertyhgb.knowflow.enterprise.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import org.apache.ibatis.annotations.Mapper;

/**
 * 企业表 Mapper，暂不编写自定义 SQL，使用 BaseMapper 提供的基础 CRUD。
 */
@Mapper
public interface EnterpriseMapper extends BaseMapper<Enterprise> {
}
