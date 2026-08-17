package io.github.qwertyhgb.knowflow.mq.consumer;

import io.github.qwertyhgb.knowflow.mq.message.DocumentParseMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 文档解析消息的死信消费者。
 *
 * <p>监听死信队列 {@code knowflow.mq.parse-dlq}：能到达这里的消息，都经历了
 * 「意外异常 → 应用内重试耗尽 → reject（不 requeue）→ DLX 转发」的完整链路，
 * 说明自动恢复已经失败，需要人工介入。</p>
 *
 * <p><strong>DLQ 的职责是「暂存 + 告警」</strong>：失败消息既不会无限 requeue 循环
 * （由 {@code default-requeue-rejected: false} 挡住），也不会静默丢失
 * （broker 按死信配置把它持久化到 DLQ）。后续可扩展人工重投能力——在 RabbitMQ
 * 管理界面（http://localhost:15672）用「Move messages」即可把消息从 DLQ 移回
 * parse-queue 重新消费。教学点：DLQ 不只是垃圾桶，配合管理工具它是人工干预的中转站，
 * 修复下游故障（如数据库恢复）后，消息依然可用。</p>
 *
 * <p><strong>这里记 ERROR 而 {@code DocumentParseConsumer} 记 WARN 的原因：</strong>
 * WARN 用于<strong>可预期</strong>的输入（重复消息、状态机拒绝等），系统自己能消化；
 * 进入 DLQ 是<strong>异常路径的终点</strong>——所有重试全部失败、必须人工排查，
 * 按项目日志规范「ERROR = 未预期且需要人工排查的失败」，这里是 ERROR 的合理场景。</p>
 *
 * <p><strong>只记日志，不重试、不抛异常：</strong></p>
 * <ul>
 *   <li>在 DLQ 消费者里重新解析大概率只是重复失败——根因通常是基础设施故障或脏数据，
 *       不是「再试一次」能解决的；</li>
 *   <li>若此处抛异常，消息会被再次拒绝，而 DLQ 本身没有配置死信，消息要么重回 DLQ
 *       无限循环、要么被丢弃，都比「留在队列里等人处理」更糟；</li>
 *   <li>因此处理逻辑刻意保持极简：记 ERROR 告警，方法正常返回即 ACK，完成告警职责。</li>
 * </ul>
 */
@Slf4j
@Component
public class DocumentParseDeadLetterConsumer {

    /**
     * 处理进入死信队列的文档解析消息。
     *
     * <p>队列名来自配置 {@code knowflow.mq.parse-dlq}，与 {@code MqConfig} 声明的
     * 死信队列一致。</p>
     *
     * @param message 死信消息（documentId / enterpriseId / knowledgeBaseId），
     *                三个 ID 足以在管理后台与数据库中定位失败现场
     */
    @RabbitListener(queues = "${knowflow.mq.parse-dlq}")
    public void handleDeadLetter(DocumentParseMessage message) {
        // 日志只记录系统标识（白名单字段），供人工按 documentId 排查失败原因。
        log.error("event=document_parse_dead_letter documentId={} enterpriseId={} knowledgeBaseId={}"
                        + "，消息进入死信队列，请人工排查",
                message.documentId(), message.enterpriseId(), message.knowledgeBaseId());
    }
}
