package io.github.qwertyhgb.knowflow.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.ai.enums.AiMessageRole;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * AI 会话消息实体，对应表 {@code ai_message}。
 *
 * <p>会话内的每条对话记录（用户问 + 助手答各一行）。本实体是多轮对话的持久化底座：
 * 每轮对话把用户消息与助手消息各存一行，再次提问时读取最近 N 条作为历史上下文
 * （滑动窗口策略见 {@code ConversationChatServiceImpl}）。</p>
 *
 * <p>token 字段（{@link #inputTokens}/{@link #outputTokens}）来自模型响应的 usage 信息，
 * 不同模型返回能力不一，缺失时为 {@code null}——Token 统计是观察性数据，缺失不阻塞主流程。</p>
 */
@Getter
@Setter
@TableName("ai_message")
public class AiMessage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID，归属 {@code ai_conversation.id}。 */
    private Long conversationId;

    /** 消息角色：USER（用户）/ ASSISTANT（助手）。 */
    private AiMessageRole role;

    /** 消息内容（UTF-8）。 */
    private String content;

    /** 输入 token 数（LLM usage），模型未返回时为 null。 */
    private Integer inputTokens;

    /** 输出 token 数（LLM usage），模型未返回时为 null。 */
    private Integer outputTokens;

    /**
     * 引用来源（JSON 字符串，仅 ASSISTANT 消息在 RAG 对话时存储）。
     *
     * <p>结构对应 {@code List<RagCitationVO>}，序列化与反序列化在业务层用 Jackson 处理。
     * 实体层保持原始 JSON 字符串，与数据库列一一对应——不在实体上直接用 {@code List<RagCitationVO>}
     * 类型，避免 MyBatis-Plus 在不同场景（MySQL / H2）自动类型转换的行为漂移。</p>
     *
     * <p>为什么可空：用户消息 (USER) 不会有引用；普通对话的助手消息 (ASSISTANT) 也不会有引用；
     * 只有会话内 RAG 对话 (ragChat / ragChatStream) 的助手回答才存储引用 JSON。</p>
     */
    private String citations;

    /** 创建时间（UTC）：会话详情与历史窗口按此排序。 */
    private Instant createdAt;
}
