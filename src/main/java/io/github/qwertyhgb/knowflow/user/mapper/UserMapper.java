package io.github.qwertyhgb.knowflow.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.qwertyhgb.knowflow.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper，暂不编写自定义 SQL，使用 BaseMapper 提供的基础 CRUD。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
