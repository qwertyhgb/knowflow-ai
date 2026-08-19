package io.github.qwertyhgb.knowflow.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketReplyRole;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 工单回复实体,对应表 {@code ticket_reply}(V15)。
 *
 * <p>工单的处理过程是多方对话流:客户补充(USER)、AI 自动回答(AI)、
 * 客服处理回复(SUPPORT)。一条回复一行,按 {@code created_at} 升序回放。</p>
 *
 * <p><strong>为什么 sender_id 对 AI 回复为 null:</strong>
 * USER/SUPPORT 回复有登录用户,sender_id 记录「谁说的」;AI 是系统服务,
 * 不是 {@code sys_user} 表里的账号,没有用户身份——强行挂一个「AI 虚拟用户」
 * 会引入假数据。AI 回复的「谁说的」由 {@code role='AI'} 表达。</p>
 *
 * <p><strong>为什么 AI 回复也存 citations:</strong>
 * AI 回答基于检索到的知识库块,引用来源(文档/文件名/块文本/相似度)持久化后,
 * 工单后期查看仍可溯源「AI 当时依据什么作答」——引用可溯源是建立对 AI 信任的
 * 基础,与 {@code ai_message.citations}(V13)同一设计。</p>
 */
@Getter
@Setter
@TableName("ticket_reply")
public class TicketReply {

    /** 回复 ID,自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属工单 ID:工单详情按此反查全部回复。 */
    private Long ticketId;

    /** 回复角色(USER/AI/SUPPORT),前端按角色渲染气泡。 */
    private TicketReplyRole role;

    /** 回复人用户 ID:USER/SUPPORT 回复记录谁说的;AI 回复无用户身份,恒为 NULL。 */
    private Long senderId;

    /** 回复内容(AI 回答/客户补充/客服处理回复)。 */
    private String content;

    /** AI 回答的引用来源 JSON(List&lt;RagCitationVO&gt; 序列化),仅 AI 回复可能有;转人工提示存 NULL。 */
    private String citations;

    /** 创建时间(UTC):工单详情按此升序排列。 */
    private Instant createdAt;
}
