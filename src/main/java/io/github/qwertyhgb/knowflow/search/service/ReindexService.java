package io.github.qwertyhgb.knowflow.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import io.github.qwertyhgb.knowflow.knowledge.enums.DocumentStatus;
import io.github.qwertyhgb.knowflow.knowledge.mapper.DocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 索引重建（reindex）补偿服务：把 MySQL 中全部 READY 状态的文档全量重同步到 ES。
 *
 * <p><strong>reindex 的用途</strong>：</p>
 * <ol>
 *   <li><strong>Mapping / 分词器变更后的全量重同步</strong>——ES 的 Mapping 是不可变的，
 *       字段的 analyzer 只能在索引创建时指定。修改 analyzer（如从 standard → ik_max_word）
 *       必须「删索引重建 + 全量重新同步数据」。本服务就是做这个「全量重新同步」的。</li>
 *   <li><strong>ES 副本丢失/损坏后的恢复</strong>——之前「写 ES 失败降级 WARN」的补偿承诺
 *       在此兑现：如果 ES 索引因硬件故障或误操作被删除/损坏，通过本服务从 MySQL 全量重同步
 *       即可恢复，不需要重新上传文档。</li>
 * </ol>
 *
 * <p><strong>当前触发方式</strong>：本步不做 HTTP 接口（学习阶段通过控制台/测试调用即可）。
 * 真实系统会做成运维接口（POST /admin/reindex）或定时任务（Spring @Scheduled），
 * 后续可演进。</p>
 *
 * <p><strong>分页扫描设计</strong>：使用 MyBatis-Plus 分页查询，每页 100 条。
 * 分页防止一次加载全表内存爆炸——企业有数万篇 READY 文档时，一次全量加载会撑爆 JVM 堆。
 * 单条失败记 WARN 不中断——补偿机制要能跑完，单条失败不影响其余文档的重同步。</p>
 */
@Slf4j
@Component
public class ReindexService {

    /** 每页文档数：分页扫描防止一次加载全表内存爆炸。 */
    private static final int PAGE_SIZE = 100;

    private final DocumentMapper documentMapper;

    private final DocumentIndexService documentIndexService;

    public ReindexService(DocumentMapper documentMapper, DocumentIndexService documentIndexService) {
        this.documentMapper = documentMapper;
        this.documentIndexService = documentIndexService;
    }

    /**
     * 全量重同步 MySQL 中全部 READY 文档到 ES。
     *
     * <p>从 MySQL 分页扫描全部 {@code status = READY} 的文档，逐条调用
     * {@link DocumentIndexService#indexDocument(Document)} 写入 ES。</p>
     */
    public void reindexAllReadyDocuments() {
        // 分页扫描：从第 1 页开始，每页 100 条，直到空页停止。
        long currentPage = 1;
        long totalReindexed = 0;
        long totalFailed = 0;

        // 构造查询条件：只扫描 READY 状态的文档（非 READY 的文档内容不可用于检索）。
        LambdaQueryWrapper<Document> queryWrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getStatus, DocumentStatus.READY)
                .orderByAsc(Document::getId);

        Page<Document> page;
        do {
            // MyBatis-Plus 分页查询：传入当前页号与每页大小。
            page = documentMapper.selectPage(new Page<>(currentPage, PAGE_SIZE), queryWrapper);
            for (Document document : page.getRecords()) {
                try {
                    documentIndexService.indexDocument(document);
                    totalReindexed++;
                } catch (Exception e) {
                    // 单条失败记 WARN 不中断：补偿机制要能跑完，单条失败不影响其余文档的重同步。
                    log.warn("event=reindex_single_failed documentId={} enterpriseId={}",
                            document.getId(), document.getEnterpriseId(), e);
                    totalFailed++;
                }
            }
            currentPage++;
        } while (page.getRecords().size() >= PAGE_SIZE);

        log.info("event=reindex_finished totalReindexed={} totalFailed={}", totalReindexed, totalFailed);
    }
}