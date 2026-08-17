package io.github.qwertyhgb.knowflow.mq.config;

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
 * <p>声明文档解析业务所需的 Exchange、Queue 与 Binding，以及全局消息转换器。</p>
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

    public MqConfig(
            @Value("${knowflow.mq.parse-exchange}") String parseExchange,
            @Value("${knowflow.mq.parse-queue}") String parseQueue,
            @Value("${knowflow.mq.parse-routing-key}") String parseRoutingKey) {
        this.parseExchange = parseExchange;
        this.parseQueue = parseQueue;
        this.parseRoutingKey = parseRoutingKey;
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
     */
    @Bean
    public Queue parseQueue() {
        return new Queue(parseQueue, true);
    }

    /**
     * 文档解析绑定：把队列绑定到交换器，按 routing key 精确路由。
     */
    @Bean
    public Binding parseBinding(DirectExchange parseExchange, Queue parseQueue) {
        return BindingBuilder.bind(parseQueue).to(parseExchange).with(parseRoutingKey);
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