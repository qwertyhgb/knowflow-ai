package io.github.qwertyhgb.knowflow.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.knowledge.enums.DocumentStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 文档实体，对应表 {@code document}。
 *
 * <p>文档是知识库内最核心的资源单元，归属于单个企业（租户）与单个知识库。
 * 复合外键 {@code (enterprise_id, knowledge_base_id)} 引用 {@code knowledge_base
 * (enterprise_id, id)}，从数据库层面确保文档只能挂到同一企业自身的知识库下，
 * 杜绝跨企业挂文档的越权操作。</p>
 *
 * <p>文档状态机见 {@link DocumentStatus}：本步只产出 UPLOADED 状态。
 * 文件内容通过 {@link #fileHash}（SHA-256 十六进制）做企业维度去重，
 * 通过 {@link #storageKey}（含随机文件名的相对路径）定位磁盘文件。</p>
 *
 * <p>解析结果：{@link #content} 保存解析后的纯文本（AI/RAG 检索的数据源），
 * {@link #failedReason} 保存解析失败的白名单短语；两列见 V10 迁移。</p>
 *
 * <p>字段依赖已开启的驼峰映射自动转换，例如 {@code enterprise_id} 映射为
 * {@code enterpriseId}、{@code knowledge_base_id} 映射为 {@code knowledgeBaseId}。</p>
 */
@Getter
@Setter
@TableName("document")
public class Document {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属企业 ID（租户 ID），通过数据库外键关联 {@code enterprise.id}。 */
    private Long enterpriseId;

    /** 所属知识库 ID，通过数据库复合外键关联 {@code knowledge_base}。 */
    private Long knowledgeBaseId;

    /** 上传者用户 ID，通过数据库外键关联 {@code sys_user.id}。 */
    private Long uploaderUserId;

    /** 原始文件名（含扩展名），仅文件名部分，不含路径。 */
    private String fileName;

    /** 文件大小（字节）。 */
    private Long fileSize;

    /** MIME 类型，可为空。 */
    private String contentType;

    /** 文件内容 SHA-256 十六进制（64 字符），重复文件检测依据。 */
    private String fileHash;

    /** 本地存储相对路径（含随机文件名与扩展名），存储层唯一。 */
    private String storageKey;

    /** 文档状态：默认 UPLOADED，详见 {@link DocumentStatus} 状态机。 */
    private DocumentStatus status;

    /** 解析后的纯文本内容（V10 新增），NULL 表示尚未解析成功（UPLOADED/FAILED 时为空）。 */
    private String content;

    /** 解析失败原因（V10 新增）：只存白名单固定短语，成功为 NULL。 */
    private String failedReason;

    /** 创建时间（UTC）。 */
    private Instant createdAt;

    /** 更新时间（UTC）。 */
    private Instant updatedAt;
}