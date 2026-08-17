package io.github.qwertyhgb.knowflow.mq.consumer;

import io.github.qwertyhgb.knowflow.mq.message.DocumentParseMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 文档解析死信消费者单元测试。
 *
 * <p>纯 JUnit 测试：{@link DocumentParseDeadLetterConsumer} 没有注入依赖，无需 Mock。
 * 死信消费者的职责只有「记 ERROR 告警」，测试验证它收到消息后不抛异常——
 * 方法正常返回即 ACK，消息不会再次被拒绝、不会重回队列循环。
 * 日志内容不直接断言，通过「调用无副作用（不抛出）」验证行为契约。</p>
 */
class DocumentParseDeadLetterConsumerTest {

    private final DocumentParseDeadLetterConsumer consumer = new DocumentParseDeadLetterConsumer();

    @Test
    void shouldNotThrowWhenHandlingDeadLetter() {
        DocumentParseMessage message = new DocumentParseMessage(999999L, 10L, 100L);

        assertDoesNotThrow(() -> consumer.handleDeadLetter(message),
                "死信消费者只记日志告警，不得抛异常（否则消息再次被拒绝，造成 DLQ 内循环或丢失）");
    }
}
