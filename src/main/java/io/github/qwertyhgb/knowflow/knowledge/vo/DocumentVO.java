package io.github.qwertyhgb.knowflow.knowledge.vo;

import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import io.github.qwertyhgb.knowflow.knowledge.enums.DocumentStatus;
import lombok.Getter;

import java.time.Instant;

/**
 * 文档响应对象。
 *
 * <p>只暴露文档接口需要的字段，避免把 {@link Document} 持久化实体直接返回给客户端。
 * 不暴露 {@code fileHash} 与 {@code storageKey}——存储细节与安全敏感信息不外露。
 * 时间字段使用 {@link Instant}，由 API 序列化层统一输出为 ISO-8601 UTC 字符串；
 * {@code status} 输出语义明确的枚举名称。</p>
 */
@Getter
public class DocumentVO {

    private final Long id;

    private final Long knowledgeBaseId;

    private final String fileName;

    private final Long fileSize;

    private final String contentType;

    private final DocumentStatus status;

    private final Instant createdAt;

    private final Instant updatedAt;

    private DocumentVO(Long id, Long knowledgeBaseId, String fileName, Long fileSize,
                       String contentType, DocumentStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.knowledgeBaseId = knowledgeBaseId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 由文档实体构建安全的 API 响应对象。 */
    public static DocumentVO from(Document document) {
        return new DocumentVO(
                document.getId(),
                document.getKnowledgeBaseId(),
                document.getFileName(),
                document.getFileSize(),
                document.getContentType(),
                document.getStatus(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}