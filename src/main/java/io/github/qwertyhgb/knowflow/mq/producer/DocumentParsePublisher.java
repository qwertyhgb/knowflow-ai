package io.github.qwertyhgb.knowflow.mq.producer;

import io.github.qwertyhgb.knowflow.mq.message.DocumentParseMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文档解析消息发布者。
 *
 * <p>上传文档后，业务层调用 {@link #publish(DocumentParseMessage)} 发送解析请求。
 * 消费者（下一步实现）收到消息后从磁盘读取文件、提取纯文本、更新文档状态。</p>
 *
 * <p><strong>生产者职责边界：</strong></p>
 * <ul>
 *   <li>生产者只负责把消息正确地交给交换器，不关心消息最终被哪个队列消费、
 *       也不关心消费者是否成功处理——那是交换器和消费者的职责；</li>
 *   <li>{@link RabbitTemplate#convertAndSend} 经 {@code JacksonJsonMessageConverter}
 *       （Jackson 3 版）自动把 {@link DocumentParseMessage} 序列化为 JSON 字节；</li>
 *   <li>本阶段不做发布确认（Publisher Confirm）——消息一旦发送到交换器即为成功，
 *       不等待 broker 确认写入。消息可靠性进阶主题（Confirm / Return / 持久化）
 *       后续单独学习。</li>
 * </ul>
 */
@Component
public class DocumentParsePublisher {

    private final RabbitTemplate rabbitTemplate;

    private final String exchange;

    private final String routingKey;

    public DocumentParsePublisher(RabbitTemplate rabbitTemplate,
                                  @Value("${knowflow.mq.parse-exchange}") String exchange,
                                  @Value("${knowflow.mq.parse-routing-key}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    /**
     * 发布文档解析消息。
     *
     * @param message 文档解析消息（含 documentId / enterpriseId / knowledgeBaseId）
     */
    public void publish(DocumentParseMessage message) {
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
    }
}