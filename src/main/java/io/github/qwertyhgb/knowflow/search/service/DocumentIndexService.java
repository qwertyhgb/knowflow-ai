package io.github.qwertyhgb.knowflow.search.service;

import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import io.github.qwertyhgb.knowflow.search.entity.DocumentIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Component;

/**
 * 文档索引写入服务：把 MySQL 的 {@link Document}（事实源）同步为 ES 的
 * {@link DocumentIndex}（检索副本）。
 *
 * <p><strong>主从副本模型</strong>：MySQL {@code document} 表是权威数据（事实源），
 * ES 索引只是它的检索副本——搜索走 ES，业务读写走 MySQL。副本写入失败不应影响主库
 * 业务：调用方把本服务的异常视为「副本同步失败」，文档保持 READY（内容已在 MySQL），
 * 记 WARN 降级；ES 副本可通过重建索引（reindex，后续步骤提供）补偿恢复。
 * 这与 Phase 7 的「消息发布失败不阻塞上传」是同一思维：<strong>异步链路失败 →
 * 主链路不受影响 → 补偿机制兜底</strong>（最终一致性）。</p>
 *
 * <p><strong>为什么不用 JSON/对象序列化混在一起做</strong>：通过
 * {@link ElasticsearchOperations#save(Object)} 写入时，Spring Data 会按
 * {@code @Document} 注解的字段序列化实体；这里显式做字段组装，是因为两个模型的
 * 字段语义并不一一对应（例如 MySQL 的 {@code id} 对应 ES 的 {@code documentId}），
 * 显式拷贝可以防止 MySQL 实体结构变化悄悄影响 ES 结构——改 ES 结构必须改本方法。
 * </p>
 */
@Slf4j
@Component
public class DocumentIndexService {

    /**
     * Spring Data Elasticsearch 的操作入口：{@code save} 按 {@code @Id} 索引写入。
     */
    private final ElasticsearchOperations operations;

    public DocumentIndexService(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    /**
     * 把一篇文档写入 ES 索引（调用方保证传入的是 READY 状态的文档）。
     *
     * <p><strong>save 的幂等性</strong>：{@code DocumentIndex.documentId} 直接用作
     * ES {@code _id}，{@code save} 是「按 {@code _id} 覆盖写」——同一文档重复索引
     * 不会产生重复文档，天然幂等（对应 DocumentIndex 类注释中「业务 ID 作 _id」的设计）。</p>
     *
     * <p><strong>为什么本步不引入 MQ 消息链</strong>：写 ES 是毫秒级操作，在解析的
     * 后台线程里同步执行完全可接受；从「解析 → ES 直连」到「解析 → 消息 → 消费 → ES」
     * 多一跳意味着多一处失败点与延迟，当前教学阶段不值得为直连叠加复杂度。等出现
     * 真正的削峰/解耦需求再演进。</p>
     *
     * @param document 已解析完成的文档实体（status = READY，content 非空）
     * @throws RuntimeException ES 写入失败时向上抛出，由调用方决定降级策略
     */
    public void indexDocument(Document document) {
        // 显式字段组装：属性名与类型逐一对应，杜绝「字段改了 ES 悄悄跟着变」。
        DocumentIndex docIndex = new DocumentIndex();
        docIndex.setDocumentId(document.getId());
        docIndex.setEnterpriseId(document.getEnterpriseId());
        docIndex.setKnowledgeBaseId(document.getKnowledgeBaseId());
        docIndex.setFileName(document.getFileName());
        docIndex.setContentType(document.getContentType());
        docIndex.setContent(document.getContent());
        docIndex.setCreatedAt(document.getCreatedAt());

        // 按 _id 覆盖写入（幂等），失败向上抛给调用方降级。
        operations.save(docIndex);

        // 只记录系统标识，不记录文件名与内容本体（日志白名单原则）。
        log.info("event=document_indexed documentId={} enterpriseId={}",
                docIndex.getDocumentId(), docIndex.getEnterpriseId());
    }
}