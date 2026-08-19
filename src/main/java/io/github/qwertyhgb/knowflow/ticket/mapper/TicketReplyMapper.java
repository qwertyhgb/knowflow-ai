package io.github.qwertyhgb.knowflow.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.qwertyhgb.knowflow.ticket.entity.TicketReply;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单回复表的持久化访问接口,只处理 {@link TicketReply} 实体。
 *
 * <p>当前不编写自定义 SQL,使用 {@link BaseMapper} 提供的基础 CRUD;
 * 「工单的全部回复」用 LambdaQueryWrapper 按 ticketId 过滤 + created_at 升序。</p>
 */
@Mapper
public interface TicketReplyMapper extends BaseMapper<TicketReply> {
}
