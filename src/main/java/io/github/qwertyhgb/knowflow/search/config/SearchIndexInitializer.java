package io.github.qwertyhgb.knowflow.search.config;

import io.github.qwertyhgb.knowflow.search.entity.DocumentIndex;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

/**
 * 搜索索引初始化器：应用启动时幂等创建 {@code knowflow-document} 索引与 Mapping。
 *
 * <p><strong>为什么在启动时初始化索引</strong>：真实项目中索引生命周期由运维流程或
 * Mapping 模板（index template / component template）管理，不在应用代码里创建；
 * 学习阶段用「启动幂等创建」最直接——索引不存在则建，存在则跳过，应用重启也不会报错。</p>
 *
 * <p><strong>注解式 Mapping</strong>：{@code createWithMapping()} 会读取
 * {@link DocumentIndex} 上的 {@code @Document/@Field} 注解，自动生成 JSON Mapping
 * 并创建索引——这就是「注解式 Mapping」方式；另一种方式是手写 JSON Mapping
 * （{@code PUT /knowflow-document/_mapping}）让运维/开发手工管理，后续步骤可以对比
 * 两者的差异（注解式与代码同源、改 Java 即改 Mapping；JSON 式显式可见、但脱离代码）。</p>
 *
 * <p><strong>为什么必须排除 test profile（本步最容易踩的坑）</strong>：全量
 * {@code @SpringBootTest} 测试（FlywayMigrationTest、各 ControllerTest）都会启动
 * 完整应用上下文；若初始化器在 test profile 下也执行，启动时会尝试连接真实 ES——
 * 测试环境没有 ES，所有测试立刻全挂。用 {@code @Profile("!test")} 让本配置类
 * 在 test profile 下根本不注册（测试用 H2 + application-test.yml，不需要也不应连 ES）。
 * 另外，Spring Data 的 ES 自动配置是「懒连接」：只创建 Client Bean、实际请求时才
 * 建立连接，所以光加依赖不会让已有测试失败——真正的风险点就是本组件。</p>
 */
@Configuration
@Profile("!test")
public class SearchIndexInitializer {

    /**
     * 注册一个应用启动检查：索引不存在则按注解创建，已存在则跳过（幂等）。
     *
     * <p>用 {@link ApplicationRunner} 而非启动时直接用 {@code @PostConstruct}：
     * Runner 在整个上下文（数据源、MQ、ES Client 等）就绪后才执行，
     * 顺序明确，也方便单元测试不用它时单独剔除。</p>
     */
    @Bean
    public ApplicationRunner ensureSearchIndex(ElasticsearchOperations operations) {
        return args -> {
            IndexOperations indexOps = operations.indexOps(DocumentIndex.class);
            if (!indexOps.exists()) {
                indexOps.createWithMapping();
            }
        };
    }
}