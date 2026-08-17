package io.github.qwertyhgb.knowflow.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档表的持久化访问接口，只处理 {@link Document} 实体。
 *
 * <p>当前不编写自定义 SQL，使用 {@link BaseMapper} 提供的基础 CRUD。</p>
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}