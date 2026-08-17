package io.github.qwertyhgb.knowflow.mq.consumer;

import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.knowledge.service.DocumentService;
import io.github.qwertyhgb.knowflow.mq.message.DocumentParseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 文档解析消费者单元测试。
 *
 * <p>纯 Mockito 测试，不启动 Spring 上下文（@RabbitListener 注解由 Spring AMQP 在
 * 运行时处理，单测只验证 handle 方法的业务逻辑）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentParseConsumerTest {

    @Mock
    private DocumentService documentService;

    private DocumentParseConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new DocumentParseConsumer(documentService);
    }

    @Test
    void shouldDelegateToInternalParse() {
        // DocumentParseMessage(documentId, enterpriseId, knowledgeBaseId)
        DocumentParseMessage message = new DocumentParseMessage(100L, 10L, 100L);

        consumer.handle(message);

        // 消费者调用 parseDocumentInternal(enterpriseId, knowledgeBaseId, documentId)
        verify(documentService).parseDocumentInternal(eq(10L), eq(100L), eq(100L));
    }

    @Test
    void shouldSwallowBusinessExceptionWhenDocumentStatusNotAllowed() {
        // 重复消息场景：第二次消费时文档已 READY，状态机拒绝（DOCUMENT_STATUS_NOT_ALLOWED）。
        // 消费端捕获并记 WARN，视为消费成功，避免 AUTO 确认下无限 requeue 死循环。
        DocumentParseMessage message = new DocumentParseMessage(100L, 10L, 100L);
        doThrow(new BusinessException(ErrorCode.DOCUMENT_STATUS_NOT_ALLOWED))
                .when(documentService)
                .parseDocumentInternal(eq(10L), eq(100L), eq(100L));

        assertDoesNotThrow(() -> consumer.handle(message),
                "业务异常应被消费端吞掉，不得向外传播触发 requeue");
        verify(documentService).parseDocumentInternal(eq(10L), eq(100L), eq(100L));
    }
}
