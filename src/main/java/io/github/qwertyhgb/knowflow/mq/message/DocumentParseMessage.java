package io.github.qwertyhgb.knowflow.mq.message;

/**
 * 文档解析消息。
 *
 * <p>上传文档后，生产者把该消息发到 RabbitMQ，消费者据此从磁盘读文件开始解析。</p>
 *
 * <p><strong>为什么只带三个 ID 不带文件内容：</strong></p>
 * <ul>
 *   <li>文件已通过 {@code uploadDocument} 写入磁盘（{@code localDir / enterpriseId /
 *       knowledgeBaseId / storageKey}），消费者按 {@code storageKey} 从磁盘读取即可；</li>
 *   <li>消息体应尽量轻量：只传递足以定位资源的关键 ID，不传输文件二进制或大文本，
 *       避免消息体过大导致 broker 性能下降或内存溢出；</li>
 *   <li>磁盘路径是存储层实现细节，消费者侧同样能通过 {@code storageKey} 构造，
 *       无需在消息中携带。</li>
 * </ul>
 *
 * @param documentId      目标文档 ID（主键），消费者按此定位文档记录
 * @param enterpriseId    所属企业 ID（租户），用于多租户隔离与磁盘路径构造
 * @param knowledgeBaseId 所属知识库 ID，用于磁盘路径构造与安全校验
 */
public record DocumentParseMessage(
        Long documentId,
        Long enterpriseId,
        Long knowledgeBaseId
) {
}