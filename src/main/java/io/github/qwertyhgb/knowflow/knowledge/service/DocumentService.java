package io.github.qwertyhgb.knowflow.knowledge.service;

import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import io.github.qwertyhgb.knowflow.knowledge.vo.DocumentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档业务服务。
 */
public interface DocumentService {

    /**
     * 上传文档到指定知识库。
     *
     * <p>流程：资源权限校验 → 文件校验（非空、扩展名白名单、大小上限）→
     * SHA-256 哈希去重 → 写入本地磁盘 → 数据库记录插入。
     * 数据库事务只覆盖记录插入；文件写入在事务前完成，插入失败需手动清理文件
     * （磁盘操作无法回滚，这是教学点，见实现注释）。</p>
     *
     * <p><strong>权限：</strong>需知识库 EDITOR/ADMIN 或企业 OWNER/ADMIN
     * （两级编辑者取或，与两级管理员模型一致，但这里放宽到 EDITOR 即可上传）。
     * 不加 {@code @PreAuthorize}（沿用权限分层模型）。</p>
     *
     * @param userId          当前登录用户 ID
     * @param enterpriseId    目标企业 ID
     * @param knowledgeBaseId 目标知识库 ID
     * @param file            上传的 multipart 文件
     * @return 上传成功的文档实体
     */
    Document uploadDocument(Long userId, Long enterpriseId, Long knowledgeBaseId, MultipartFile file);

    /**
     * 列出知识库下当前用户可见的全部文档。
     *
     * <p>可见性规则与 {@code KnowledgeBaseServiceImpl.getKnowledgeBase} 完全复用：
     * 知识库对当前用户可见（成员可见 / PUBLIC 非成员可见 / PRIVATE 非成员 404）
     * 是查看文档列表的前提；知识库不存在或已禁用按 KNOWLEDGE_BASE_NOT_FOUND 处理；
     * 非企业成员按 FORBIDDEN 处理（先资源后权限）。</p>
     *
     * <p><strong>权限：</strong>知识库可见即可查看（VIEWER 可读）；由 Service 按
     * {@code requireVisibleKnowledgeBase} 校验，不加 {@code @PreAuthorize}。</p>
     *
     * @param userId          当前登录用户 ID
     * @param enterpriseId    目标企业 ID
     * @param knowledgeBaseId 目标知识库 ID
     * @return 文档 VO 列表，按创建时间倒序、ID 倒序；空列表返回 {@link List#of()}
     */
    List<DocumentVO> listDocuments(Long userId, Long enterpriseId, Long knowledgeBaseId);

    /**
     * 定位文档并校验可见性、磁盘文件存在性，供下载使用。
     *
     * <p>下载 = 查看，可见即可下载：先复用 {@code requireVisibleKnowledgeBase} 完成
     * 知识库维度的可见性与成员身份校验，再用「id + enterpriseId + knowledgeBaseId」
     * 三条件定位文档（防跨企业跨知识库越权）。磁盘文件必须存在，否则按存储异常处理。</p>
     *
     * <p><strong>权限：</strong>知识库可见即可下载（VIEWER 可读可下载）。</p>
     *
     * @param userId          当前登录用户 ID
     * @param enterpriseId    目标企业 ID
     * @param knowledgeBaseId 目标知识库 ID
     * @param documentId      目标文档 ID
     * @return 文档实体（含 storageKey 等，由 Controller 负责流式输出）
     */
    Document getDocumentForDownload(Long userId, Long enterpriseId, Long knowledgeBaseId, Long documentId);

    /**
     * 解析文档：提取文件内容为纯文本并驱动状态机流转。
     *
     * <p><strong>状态机：</strong>仅 {@code UPLOADED} 可解析；解析前置为
     * {@code PARSING}（提前落库防并发重复解析），成功 → {@code READY} 且写入
     * {@code content}，失败 → {@code FAILED} 且写入白名单短语 {@code failed_reason}。
     * 解析失败是文档的正常终态，<strong>不抛出异常</strong>——调用方看返回实体的
     * {@code status} 即可，无需 try/catch。</p>
     *
     * <p><strong>权限：</strong>与上传一致，需知识库 EDITOR/ADMIN 或企业 OWNER/ADMIN
     * （{@code requireKnowledgeBaseEditor}，两级编辑者取或）。</p>
     *
     * <p><strong>外部入口：</strong>本方法是带权限校验的外部入口，供 HTTP 接口
     * （手动触发解析 {@code POST .../parse}）调用。内部核心逻辑见
     * {@link #parseDocumentInternal(Long, Long, Long)}。</p>
     *
     * @param userId          当前登录用户 ID
     * @param enterpriseId    目标企业 ID
     * @param knowledgeBaseId 目标知识库 ID
     * @param documentId      目标文档 ID
     * @return 解析后的文档实体（status 为 READY 或 FAILED）
     */
    Document parseDocument(Long userId, Long enterpriseId, Long knowledgeBaseId, Long documentId);

    /**
     * 解析文档（内部方法）：与 {@link #parseDocument(Long, Long, Long, Long)} 相同的
     * 状态机逻辑，但<strong>不做任何权限校验</strong>。
     *
     * <p><strong>信任边界：</strong>本方法仅限可信内部调用者（MQ 消费者）使用——
     * 队列内消息只来自本系统生产者、进入本系统声明的队列，消费时不存在「登录用户」概念，
     * 因此无需（也无法）做用户级权限校验。外部访问（HTTP 接口）必须走带权限校验的
     * {@link #parseDocument(Long, Long, Long, Long)}，禁止绕过权限直接调用本方法。</p>
     *
     * @param enterpriseId    目标企业 ID
     * @param knowledgeBaseId 目标知识库 ID
     * @param documentId      目标文档 ID
     * @return 解析后的文档实体（status 为 READY 或 FAILED）
     */
    Document parseDocumentInternal(Long enterpriseId, Long knowledgeBaseId, Long documentId);
}
