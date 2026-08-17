package io.github.qwertyhgb.knowflow.mq.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQ 基础设施配置单元测试。
 *
 * <p>纯 JUnit 不启动 Spring 上下文：手动 new {@link MqConfig}，逐个断言各 Bean 的
 * 配置属性正确。不验证框架内部的 Binder 逻辑，只验证本项目声明的配置语义。</p>
 */
class MqConfigTest {

    private static final String EXCHANGE = "knowflow.document.parse.exchange";
    private static final String QUEUE = "knowflow.document.parse.queue";
    private static final String ROUTING_KEY = "knowflow.document.parse";
    private static final String DLX = "knowflow.document.parse.dlx";
    private static final String DLQ = "knowflow.document.parse.dlq";
    private static final String DLQ_ROUTING_KEY = "knowflow.document.parse.dlq";

    private final MqConfig config = new MqConfig(
            EXCHANGE, QUEUE, ROUTING_KEY, DLX, DLQ, DLQ_ROUTING_KEY);

    @Test
    void shouldCreateDirectExchange() {
        DirectExchange exchange = config.parseExchange();
        assertNotNull(exchange);
        assertEquals(EXCHANGE, exchange.getName());
        // DirectExchange 默认不持久化，这里不 assert durable（默认 false 不影响业务）
    }

    @Test
    void shouldCreateDurableQueue() {
        Queue queue = config.parseQueue();
        assertNotNull(queue);
        assertEquals(QUEUE, queue.getName());
        assertTrue(queue.isDurable(), "解析队列必须持久化，broker 重启后声明仍保留");
    }

    @Test
    void shouldDeclareDeadLetterArgumentsOnParseQueue() {
        // 队列参数声明死信去向：被拒绝且不 requeue 的消息由 broker 转发到 DLX，
        // 并按 x-dead-letter-routing-key 路由进 DLQ。
        Queue queue = config.parseQueue();
        assertEquals(DLX, queue.getArguments().get("x-dead-letter-exchange"),
                "解析队列必须声明死信交换器，重试耗尽的消息才能被转发到 DLQ");
        assertEquals(DLQ_ROUTING_KEY, queue.getArguments().get("x-dead-letter-routing-key"),
                "死信路由键必须与 DLQ 绑定路由键一致，否则死信消息无法落入 DLQ");
    }

    @Test
    void shouldCreateDeadLetterExchange() {
        DirectExchange dlx = config.parseDlx();
        assertNotNull(dlx);
        assertEquals(DLX, dlx.getName());
    }

    @Test
    void shouldCreateDurableDeadLetterQueue() {
        Queue dlq = config.parseDlq();
        assertNotNull(dlq);
        assertEquals(DLQ, dlq.getName());
        assertTrue(dlq.isDurable(), "死信队列必须持久化，失败消息在人工排查前不能丢失");
    }

    @Test
    void shouldBindDlqToDlxWithDlqRoutingKey() {
        DirectExchange dlx = config.parseDlx();
        Queue dlq = config.parseDlq();
        Binding binding = config.parseDlqBinding(dlx, dlq);

        assertNotNull(binding);
        assertEquals(DLQ, binding.getDestination());
        assertEquals(DLX, binding.getExchange());
        assertEquals(DLQ_ROUTING_KEY, binding.getRoutingKey());
        assertEquals(Binding.DestinationType.QUEUE, binding.getDestinationType());
    }

    @Test
    void shouldBindQueueToExchangeWithRoutingKey() {
        DirectExchange exchange = config.parseExchange();
        Queue queue = config.parseQueue();
        Binding binding = config.parseBinding(exchange, queue);

        assertNotNull(binding);
        assertEquals(QUEUE, binding.getDestination());
        assertEquals(EXCHANGE, binding.getExchange());
        assertEquals(ROUTING_KEY, binding.getRoutingKey());
        assertEquals(Binding.DestinationType.QUEUE, binding.getDestinationType());
    }

    @Test
    void shouldCreateJsonMessageConverter() {
        MessageConverter converter = config.jsonMessageConverter();
        assertNotNull(converter);
        assertInstanceOf(JacksonJsonMessageConverter.class, converter,
                "消息转换器应为 JacksonJsonMessageConverter（Jackson 3 版），确保 JSON 序列化");
    }
}