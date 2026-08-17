package io.github.qwertyhgb.knowflow.mq.producer;

import io.github.qwertyhgb.knowflow.mq.message.DocumentParseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.verify;

/**
 * 文档解析消息发布者单元测试。
 *
 * <p>纯 Mockito 测试，不启动 Spring 上下文。验证 {@link DocumentParsePublisher#publish}
 * 按预期调用 {@link RabbitTemplate#convertAndSend}。</p>
 *
 * <p>注意不能用 {@code @InjectMocks}：构造器的 {@code @Value} 字符串参数无法由 Mockito
 * 注入，因此手动 new 并显式传入 exchange / routing key。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentParsePublisherTest {

    private static final String EXCHANGE = "knowflow.document.parse.exchange";
    private static final String ROUTING_KEY = "knowflow.document.parse";

    @Mock
    private RabbitTemplate rabbitTemplate;

    private DocumentParsePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new DocumentParsePublisher(rabbitTemplate, EXCHANGE, ROUTING_KEY);
    }

    @Test
    void shouldPublishDocumentParseMessage() {
        // 构造一条解析消息
        DocumentParseMessage message = new DocumentParseMessage(100L, 1L, 10L);

        publisher.publish(message);

        // 验证参数正确传递：exchange / routing key / message 三个参数
        // convertAndSend 内部会经 JacksonJsonMessageConverter 自动序列化
        verify(rabbitTemplate).convertAndSend(EXCHANGE, ROUTING_KEY, message);
    }
}