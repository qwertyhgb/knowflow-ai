package io.github.qwertyhgb.knowflow.mq.consumer;

import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.knowledge.service.DocumentService;
import io.github.qwertyhgb.knowflow.mq.message.DocumentParseMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 文档解析消息消费者。
 *
 * <p>监听 {@code knowflow.mq.parse-queue} 队列，收到 {@link DocumentParseMessage} 后
 * 调用 {@link DocumentService#parseDocumentInternal} 在后台完成文档解析。</p>
 *
 * <p><strong>权限边界（信任边界）：</strong>消费者不校验任何权限——队列内消息只来自
 * 本系统生产者、进入本系统声明的队列，消费时不存在「登录用户」概念，因此直接调用
 * 无权限校验的内部解析方法 {@code parseDocumentInternal}；手动解析 HTTP 接口
 * （{@code POST .../parse}）保留完整权限校验作为外部入口。详见
 * {@code DocumentService} 接口对 {@code parseDocumentInternal} 的说明。</p>
 *
 * <p><strong>@RabbitListener 默认 AUTO 确认语义：</strong></p>
 * <ul>
 *   <li>方法正常返回 = 消息确认消费成功（broker 移除消息）；</li>
 *   <li>方法抛出异常 = 消息被判定为消费失败，broker 把它重新放回队列（requeue）。</li>
 * </ul>
 *
 * <p><strong>为什么捕获 {@link BusinessException} 而不抛出：</strong></p>
 * <ul>
 *   <li>业务异常属于<strong>可预期</strong>情况：例如消息被投递两次时，第二次文档已
 *       READY，状态机校验会抛 {@code DOCUMENT_STATUS_NOT_ALLOWED}——若抛出，
 *       AUTO 模式会无限 requeue 形成<strong>死循环</strong>；</li>
 *   <li>捕获后记 WARN 即视为消费成功，这正是「<strong>消费幂等</strong>」的处理方式：
 *       重复消息不重复解析、也不触发无限重试；</li>
 *   <li>解析本身失败（坏文件）不会抛异常：{@code parseDocumentInternal} 已把 FAILED
 *       作为正常终态返回，不会触发 requeue。</li>
 * </ul>
 *
 * <p><strong>真正不可预期的异常仍会抛出</strong>（如数据库宕机）：AUTO 模式会 requeue
 * 重试，这是它的兜底行为。重试次数限制与死信队列（DLQ）是下一步的进阶主题。</p>
 */
@Slf4j
@Component
public class DocumentParseConsumer {

    private final DocumentService documentService;

    public DocumentParseConsumer(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 处理文档解析消息。
     *
     * <p>队列名来自配置 {@code knowflow.mq.parse-queue}，与 {@code MqConfig} 声明的
     * 队列一致。</p>
     *
     * @param message 文档解析消息（documentId / enterpriseId / knowledgeBaseId）
     */
    @RabbitListener(queues = "${knowflow.mq.parse-queue}")
    public void handle(DocumentParseMessage message) {
        try {
            documentService.parseDocumentInternal(
                    message.enterpriseId(), message.knowledgeBaseId(), message.documentId());
        } catch (BusinessException ex) {
            // 可预期的业务拒绝（如重复消息导致的 DOCUMENT_STATUS_NOT_ALLOWED）：
            // 记 WARN 视为消费成功，避免 AUTO 确认下的无限 requeue 死循环。
            // 日志只记录系统标识与枚举错误码（白名单），不记录异常内容。
            log.warn("event=document_parse_message_rejected documentId={} errorCode={}",
                    message.documentId(), ex.getErrorCode());
        }
    }
}
