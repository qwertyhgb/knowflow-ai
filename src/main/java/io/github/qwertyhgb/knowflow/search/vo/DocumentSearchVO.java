package io.github.qwertyhgb.knowflow.search.vo;

import io.github.qwertyhgb.knowflow.search.entity.DocumentIndex;
import lombok.Getter;
import org.springframework.data.elasticsearch.core.SearchHit;

import java.time.Instant;
import java.util.List;

/**
 * 搜索结果中的文档条目 VO。
 *
 * <p>只暴露搜索场景需要的字段，不暴露 {@link DocumentIndex} 的全文 {@code content}
 * （避免在搜索结果页把整篇文档内容全返回给前端——搜摘要而非全文）。</p>
 *
 * <p>静态工厂 {@link #from(SearchHit)} 从 ES 的 {@link SearchHit} 提取高亮片段
 * 与原始字段组装 VO：高亮片段优先展示（用户搜索时最关心命中位置），无高亮时
 * 用 {@code content} 字段前 200 字符作为摘要片段。</p>
 */
@Getter
public class DocumentSearchVO {

    private final Long documentId;

    private final Long knowledgeBaseId;

    private final String fileName;

    private final String contentType;

    /** 高亮片段（优先），无高亮时取 content 字段前 200 字符。 */
    private final String contentSnippet;

    private final Instant createdAt;

    private DocumentSearchVO(Long documentId, Long knowledgeBaseId, String fileName,
                             String contentType, String contentSnippet, Instant createdAt) {
        this.documentId = documentId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.contentSnippet = contentSnippet;
        this.createdAt = createdAt;
    }

    /**
     * 从 ES 搜索结果 {@link SearchHit} 构建 VO。
     *
     * <p><strong>高亮优先原则</strong>：ES 的 highlight 响应在匹配字段上标注命中词
     * 位置（默认 {@code <em>} 标签包围命中词），前端可直接渲染。取高亮时优先使用
     * content 字段的高亮片段，其次是 fileName 字段的高亮；若均无高亮，则截取
     * {@code content} 原始值的前 200 字符作为摘要片段。</p>
     *
     * @param hit ES 搜索命中结果
     * @return 文档搜索条目 VO
     */
    public static DocumentSearchVO from(SearchHit<DocumentIndex> hit) {
        DocumentIndex content = hit.getContent();

        // 取高亮片段：优先 content 字段高亮，其次 fileName 字段高亮，最后截取原文。
        List<String> contentHighlights = hit.getHighlightField("content");
        List<String> fileNameHighlights = hit.getHighlightField("fileName");
        String snippet = null;
        if (contentHighlights != null && !contentHighlights.isEmpty()) {
            // 高亮字段返回的是匹配片段列表，取第一条作为摘要。
            snippet = contentHighlights.getFirst();
        } else if (fileNameHighlights != null && !fileNameHighlights.isEmpty()) {
            snippet = fileNameHighlights.getFirst();
        } else if (content.getContent() != null) {
            // 无高亮时取 content 前 200 字符作为摘要，避免返回整篇文档全文。
            String raw = content.getContent();
            snippet = raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
        }

        return new DocumentSearchVO(
                content.getDocumentId(),
                content.getKnowledgeBaseId(),
                content.getFileName(),
                content.getContentType(),
                snippet,
                content.getCreatedAt());
    }
}