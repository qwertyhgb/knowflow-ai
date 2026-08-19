package io.github.qwertyhgb.knowflow.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.qwertyhgb.knowflow.ticket.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单表的持久化访问接口,只处理 {@link Ticket} 实体。
 *
 * <p>当前不编写自定义 SQL,使用 {@link BaseMapper} 提供的基础 CRUD;
 * 「我的工单」「工单详情」等查询用 LambdaQueryWrapper 在 Service 层组装条件。</p>
 */
@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {
}
