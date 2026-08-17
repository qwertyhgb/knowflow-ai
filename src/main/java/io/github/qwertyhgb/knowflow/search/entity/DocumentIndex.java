package io.github.qwertyhgb.knowflow.search.entity;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import lombok.Getter;
import lombok.Setter;

/**
 * Elasticsearch 索引文档模型，对应索引 {@code knowflow-document}。
 *
 * <p><strong>与 MySQL {@code Document} 实体的关系</strong>：
 * 两者是同一份业务数据在「事实源 / 检索副本」两个存储中的不同形态——
 * {@code Document}（{@code knowledge} 模块）是 MySQL 持久化实体，负责落库与业务读写，
 * 是数据的权威版本；{@code DocumentIndex} 是本模块的 ES 存储副本，只承载
 * 「按关键词搜索 + 按企业/知识库过滤」的检索职责。MySQL 不负责全文搜索，
 * ES 不负责业务数据落库，双写/同步逻辑由后续步骤实现。</p>
 *
 * <p><strong>只索引 READY 文档的约定</strong>：按 {@code DocumentStatus} 状态机，
 * READY 表示「解析完成、内容可被检索」——因此索引同步只把 READY 状态的文档
 * 写入本索引，处于 UPLOADED/PARSING/PARSED/INDEXING 的文档不建索引，
 * FAILED 的文档也不建索引（失败内容不可靠）。该约定由后续的索引同步逻辑兑现，
 * 本步只负责把索引与 Mapping 建好。</p>
 *
 * <p><strong>可见性过滤预留</strong>：知识库访问规则是「成员 / PUBLIC 可见，
 * PRIVATE 非成员访问返回 404」。因此本索引保留 {@code enterpriseId} 与
 * {@code knowledgeBaseId} 两个精确过滤字段，搜索接口（下一步）必须先用它们
 * 过滤出当前用户可见的知识库下的文档，再做关键词检索——ES 不感知业务权限，
 * 权限过滤必须显式写进查询。</p>
 */
@Getter
@Setter
@Document(indexName = "knowflow-document")
// @Setting：shards=1 学习阶段单分片足够（分片是 ES 横向扩展的最小单位，仅数据量大
// 到单节点扛不住时才需要拆多分片）；replicas=0 因为 compose 是单节点——默认副本数 1
// 时副本分片无处安放，集群会一直显示 yellow 健康状态，新手看到容易误以为出了故障。
@Setting(shards = 1, replicas = 0)
public class DocumentIndex {

    /**
     * 文档 ID，直接复用 MySQL {@code document.id} 作为 ES {@code _id}。
     *
     * <p>用业务主键当 {@code _id} 的好处：按文档删除/更新索引时天然能定位到对应文档
     * （删除/重建文档时按 {@code documentId} 精确操作），再次索引同一文档也是
     * 幂等覆盖写入，不会产生重复文档。ES 允许调用方指定 {@code _id} 而不强制生成。</p>
     */
    @Id
    private Long documentId;

    /**
     * 所属企业 ID（租户 ID）。
     *
     * <p>{@code FieldType.Long}：数值类型，语义等同于 keyword——只做精确过滤
     * （企业隔离），绝不参与分词检索。选用数值而非 keyword 字符串是为了与
     * MySQL {@code BIGINT} 语义对齐，且 term 查询数值比字符串更直观。</p>
     */
    @Field(type = FieldType.Long)
    private Long enterpriseId;

    /**
     * 所属知识库 ID。
     *
     * <p>与 {@link #enterpriseId} 同为精确过滤字段：{@code FieldType.Long} 整体
     * 存储、不分词，用于知识库维度的可见性过滤与按库内搜索。</p>
     */
    @Field(type = FieldType.Long)
    private Long knowledgeBaseId;

    /**
     * 原始文件名（含扩展名）。
     *
     * <p>{@code FieldType.Text}：标题类字段希望「搜文件名关键词能命中」，所以要
     * 分词——这是 ES 第一课：<strong>text 字段会被分词器拆成词项（term）存进倒排索引，
     * 支持全文匹配；keyword 字段整体作为单个词项存储，支持精确等于/过滤，不参与分词</strong>。
     * 当前使用 IK 中文分词器（{@code ik_max_word} 索引、{@code ik_smart} 搜索），
     * 中文按语义切词而非逐字切分，搜索结果更准确。</p>
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String fileName;

    /**
     * MIME 类型（如 application/pdf）。
     *
     * <p>{@code FieldType.Keyword}：keyword 字段整体存储、不建立倒排索引词项分解，
     * 适合「不搜索、只展示/过滤（如按类型筛文档）」的场景——用 keyword 而不是 text，
     * 是因为没有人会搜一半的 MIME 类型名，精确匹配就够，且省下无意义的分词开销。</p>
     */
    @Field(type = FieldType.Keyword)
    private String contentType;

    /**
     * 解析后的文档纯文本（来自 MySQL {@code document.content}）。
     *
     * <p>核心全文搜索字段：{@code FieldType.Text}，分词建倒排索引，搜索关键词
     * 在这里命中。当前使用 IK 中文分词器（{@code ik_max_word} 索引、
     * {@code ik_smart} 搜索），中文按语义切词。</p>
     *
     * <p><strong>Mapping 不可变</strong>：analyzer 只能在索引创建时指定，
     * 已建索引的字段 analyzer 无法原地修改。这是 ES 与 MySQL 的重大差异：
     * MySQL 随时可以 ALTER TABLE 修改列定义，ES 的 Mapping 一经创建字段的
     * analyzer 就不可变——变更必须「删索引重建 + 全量重同步数据」。
     * 本步同时提供 {@code ReindexService} 用于重建索引后的全量重同步。</p>
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    /**
     * 创建时间（UTC，来自 MySQL {@code document.created_at}）。
     *
     * <p>{@code FieldType.Date}：按时间排序（最新文档在前）或按时间范围过滤时要
     * 用它。ES 的 date 字段底层存时间戳（毫秒），配合 Spring Data 的转换器，
     * Java {@link Instant} 与 ISO-8601（如 2026-08-17T08:00:00Z）自动互转，
     * 与项目「API 时间统一 ISO-8601 UTC 字符串」的约定一致。</p>
     */
    @Field(type = FieldType.Date)
    private Instant createdAt;
}