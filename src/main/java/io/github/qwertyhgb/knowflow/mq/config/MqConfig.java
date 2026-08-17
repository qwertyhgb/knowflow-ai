package io.github.qwertyhgb.knowflow.mq.config;

import java.util.Map;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 消息基础设施配置。
 *
 * <p>声明文档解析业务所需的 Exchange、Queue、Binding，以及死信（DLX/DLQ）拓扑
 * 和全局消息转换器。</p>
 *
 * <p><strong>消息生命周期全图</strong>（配合 {@code application.yml} 中
 * {@code spring.rabbitmq.listener.simple} 的重试配置）：</p>
 * <pre>
 * 生产者发布 → parse-exchange → parse-queue → DocumentParseConsumer
 *   ├─ 正常消费成功 → ACK，broker 移除消息（生命周期结束）
 *   ├─ BusinessException（可预期业务拒绝，如重复消息）
 *   │     → 消费者捕获记 WARN，视为消费成功（幂等，见 DocumentParseConsumer），
 *   │       不进重试、不进 DLQ
 *   └─ 意外异常（数据库宕机等）
 *         → 应用内重试共 3 次（1 次初始 + 2 次重试，指数退避 1s → 2s）
 *         ├─ 重试成功 → ACK（生命周期结束）
 *         └─ 重试全部失败 → reject（不 requeue）
 *               → broker 按 parse-queue 的死信配置转发到 DLX → DLQ
 *               → DocumentParseDeadLetterConsumer 记 ERROR 提醒人工排查
 * </pre>
 *
 * <p><strong>为什么用 Direct Exchange 而非 Fanout：</strong></p>
 * <ul>
 *   <li><strong>Direct</strong>：消息按 routing key 精确路由到一个或多个队列，
 *       适合当前场景——「一条解析消息只进解析队列」；</li>
 *   <li><strong>Fanout</strong>：把消息广播到所有绑定的队列，适合「一个事件触发多个
 *       独立处理」的场景（如用户注册后同时发邮件、发短信、记录日志）；</li>
 *   <li>当前「文档解析」只需要一个队列消费，Direct 更精确、更易理解；后续出现广播场景
 *       再增 Fanout 即可。</li>
 * </ul>
 */
@Configuration
public class MqConfig {

    /** 文档解析交换器名称。 */
    private final String parseExchange;

    /** 文档解析队列名称。 */
    private final String parseQueue;

    /** 文档解析路由键。 */
    private final String parseRoutingKey;

    /** 死信交换器名称（Dead Letter Exchange）。 */
    private final String parseDlx;

    /** 死信队列名称（Dead Letter Queue）。 */
    private final String parseDlq;

    /** 死信队列路由键：死信消息经 DLX 重新发布时使用的 routing key。 */
    private final String parseDlqRoutingKey;

    public MqConfig(
            @Value("${knowflow.mq.parse-exchange}") String parseExchange,
            @Value("${knowflow.mq.parse-queue}") String parseQueue,
            @Value("${knowflow.mq.parse-routing-key}") String parseRoutingKey,
            @Value("${knowflow.mq.parse-dlx}") String parseDlx,
            @Value("${knowflow.mq.parse-dlq}") String parseDlq,
            @Value("${knowflow.mq.parse-dlq-routing-key}") String parseDlqRoutingKey) {
        this.parseExchange = parseExchange;
        this.parseQueue = parseQueue;
        this.parseRoutingKey = parseRoutingKey;
        this.parseDlx = parseDlx;
        this.parseDlq = parseDlq;
        this.parseDlqRoutingKey = parseDlqRoutingKey;
    }

    /**
     * 文档解析 Direct 交换器。
     *
     * <p>Direct Exchange 按 routing key 精确匹配，消息只进入 routing key 对应的队列。</p>
     */
    @Bean
    public DirectExchange parseExchange() {
        return new DirectExchange(parseExchange);
    }

    /**
     * 文档解析队列。
     *
     * <p>{@code durable=true}：持久化队列——broker 重启后声明仍保留，这是消息不丢的基础。
     * 队列不持久化的话，重启后队列消失，再持久化的消息也无法投递——白费功夫。</p>
     *
     * <p><strong>队列参数声明死信去向</strong>（{@code x-dead-letter-exchange} /
     * {@code x-dead-letter-routing-key}）：当消息「被拒绝且不重回队列」时（本项目即
     * 重试耗尽后被 reject，见 {@code application.yml} 的 {@code default-requeue-rejected: false}），
     * broker 自动把消息转发到指定的死信交换器；{@code x-dead-letter-routing-key}
     * 决定死信消息重新发布时使用的路由键，此处与 {@code parse-dlq-routing-key} 一致，
     * 保证消息精确落入 DLQ。</p>
     *
     * <p>注意：这些参数在队列<strong>声明</strong>时生效。若 broker 上已存在同名但参数
     * 不同的队列，启动会报 {@code PRECONDITION_FAILED}——学习环境中删掉旧队列重建即可。</p>
     */
    @Bean
    public Queue parseQueue() {
        return new Queue(parseQueue, true, false, false, Map.of(
                "x-dead-letter-exchange", parseDlx,
                "x-dead-letter-routing-key", parseDlqRoutingKey));
    }

    /**
     * 文档解析绑定：把队列绑定到交换器，按 routing key 精确路由。
     */
    @Bean
    public Binding parseBinding(DirectExchange parseExchange, Queue parseQueue) {
        return BindingBuilder.bind(parseQueue).to(parseExchange).with(parseRoutingKey);
    }

    /**
     * 死信交换器（DLX）。
     *
     * <p>专门接收从 parse-queue 转发来的死信消息，与业务交换器 parse-exchange 隔离——
     * 避免死信消息混进业务路由被业务消费者误消费，也便于在管理界面按交换器排查死信流向。</p>
     */
    @Bean
    public DirectExchange parseDlx() {
        return new DirectExchange(parseDlx);
    }

    /**
     * 死信队列（DLQ）：重试耗尽消息的最终落脚点。
     *
     * <p>{@code durable=true}：应用重启、broker 重启后消息仍在，保证失败消息
     * 不会在等待人工排查期间丢失。</p>
     */
    @Bean
    public Queue parseDlq() {
        return new Queue(parseDlq, true);
    }

    /**
     * 死信绑定：把 DLQ 绑定到 DLX，按死信路由键精确路由。
     */
    @Bean
    public Binding parseDlqBinding(DirectExchange parseDlx, Queue parseDlq) {
        return BindingBuilder.bind(parseDlq).to(parseDlx).with(parseDlqRoutingKey);
    }

    /**
     * JSON 消息转换器。
     *
     * <p>默认的 JDK 序列化（{@code SimpleMessageConverter}）性能差、不安全（反序列化
     * 漏洞风险）、且跨语言不可读。JSON 转换器：</p>
     * <ul>
     *   <li>消息体是 JSON 文本，浏览器/curl 可读，调试方便；</li>
     *   <li>跨语言互通：如果后续有 Python/PHP 消费者，JSON 无需额外适配；</li>
     *   <li>Spring 自动将 Java 对象序列化为 JSON，消费者反序列化时也自动恢复。</li>
     * </ul>
     *
     * <p><strong>版本说明：</strong>Spring AMQP 4.0 起，Jackson 2 系列转换器
     * （{@code Jackson2JsonMessageConverter}、{@code Jackson2JavaTypeMapper} 等）已被
     * 标记为移除（deprecated for removal），本实现使用 Jackson 3 版
     * {@link JacksonJsonMessageConverter}（包名不变，均为 {@code org.springframework.amqp
     * .support.converter}），遵循项目「必须使用最新非弃用 API」的约定。</p>
     *
     * <p>注册为 Bean 后，Spring Boot 自动配置的 {@link RabbitTemplate} 会自动使用此
     * 转换器，无需手动设置。</p>
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}