package io.github.qwertyhgb.knowflow.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.qwertyhgb.knowflow.ai.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 会话表的持久化访问接口，只处理 {@link Conversation} 实体。
 *
 * <p>当前不编写自定义 SQL，使用 {@link BaseMapper} 提供的基础 CRUD。</p>
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
