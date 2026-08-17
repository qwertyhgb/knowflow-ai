package io.github.qwertyhgb.knowflow.knowledge.controller;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.common.response.Result;
import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import io.github.qwertyhgb.knowflow.knowledge.service.DocumentService;
import io.github.qwertyhgb.knowflow.knowledge.vo.DocumentVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 文档接口（上传、列表、下载）。
 *
 * <p>接口位于企业作用域 + 知识库作用域路径，必须登录并携带与路径一致的 {@code X-Enterprise-Id}
 * 请求头；企业上下文与成员身份由 {@code EnterpriseContextFilter} 在进入控制器前统一校验。</p>
 *
 * <p><strong>权限：</strong>上传需知识库 EDITOR/ADMIN 或企业 OWNER/ADMIN；列表与下载
 * 只需知识库可见（VIEWER 可读可下载）。均由 Service 按资源级校验，不加 {@code @PreAuthorize}
 * （沿用权限分层模型——权限码无法携带资源 ID，资源级角色由 Service 判定）。</p>
 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentController {

    private final DocumentService documentService;

    /** 本地文件存储根目录，与 {@code knowflow.storage.local-dir} 配置对齐。 */
    private final String localDir;

    public DocumentController(DocumentService documentService,
                              @Value("${knowflow.storage.local-dir}") String localDir) {
        this.documentService = documentService;
        this.localDir = localDir;
    }

    /**
     * 上传文档到指定知识库。
     *
     * <p>接收 {@code multipart/form-data} 请求，文件通过 {@code file} 表单项上传。
     * 支持文件类型：pdf、docx、txt、md；单文件上限 10MB。</p>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<DocumentVO> uploadDocument(Authentication authentication,
                                             @PathVariable Long enterpriseId,
                                             @PathVariable Long knowledgeBaseId,
                                             @RequestParam("file") MultipartFile file) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        Document document = documentService.uploadDocument(userId, enterpriseId, knowledgeBaseId, file);
        return Result.success(DocumentVO.from(document));
    }

    /**
     * 列出知识库下当前用户可见的全部文档。
     *
     * <p>知识库可见即可查看（VIEWER 可读）：成员可见、PUBLIC 非成员可见、
     * PRIVATE 非成员 404（隐私设计，不泄露存在性）。</p>
     */
    @GetMapping
    public Result<List<DocumentVO>> listDocuments(Authentication authentication,
                                                  @PathVariable Long enterpriseId,
                                                  @PathVariable Long knowledgeBaseId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        List<DocumentVO> documents = documentService.listDocuments(userId, enterpriseId, knowledgeBaseId);
        return Result.success(documents);
    }

    /**
     * 下载指定文档。
     *
     * <p>流式返回不把整个文件读进内存，大文件友好；参照
     * {@link org.springframework.core.io.FileSystemResource}。</p>
     *
     * <p>中文文件名必须用 RFC 5987 {@code filename*} 格式，否则浏览器显示乱码；
     * 注意 {@link URLEncoder} 的 + 号问题——用 {@link StandardCharsets#UTF_8} 编码并替换
     * {@code +} 为 {@code %20}。</p>
     */
    @GetMapping("/{documentId}/download")
    public ResponseEntity<Resource> downloadDocument(Authentication authentication,
                                                     @PathVariable Long enterpriseId,
                                                     @PathVariable Long knowledgeBaseId,
                                                     @PathVariable Long documentId) throws Exception {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        Document document = documentService.getDocumentForDownload(
                userId, enterpriseId, knowledgeBaseId, documentId);

        // 构造文件的绝对路径：localDir / enterpriseId / knowledgeBaseId / storageKey。
        // FileSystemResource 流式返回：Spring 会分块传输文件内容，不会把整个文件读进内存。
        Path filePath = Paths.get(localDir, String.valueOf(enterpriseId),
                String.valueOf(knowledgeBaseId), document.getStorageKey());
        Resource resource = new FileSystemResource(filePath.toFile());

        // Content-Type：优先使用文档记录的 MIME 类型，为空则回退到通用二进制流。
        String contentType = document.getContentType();
        MediaType mediaType = (contentType != null && !contentType.isBlank())
                ? MediaType.parseMediaType(contentType)
                : MediaType.APPLICATION_OCTET_STREAM;

        // RFC 5987 文件名编码：中文文件名必须用 filename*=UTF-8'' 格式。
        // URLEncoder.encode 会把空格编码为 +，但 RFC 5987 要求空格为 %20，故替换。
        String encodedFileName = URLEncoder.encode(document.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        String contentDisposition = "attachment; filename*=UTF-8''" + encodedFileName;

        // Content-Length 使用 Files.size 获取实际文件大小，便于浏览器显示下载进度。
        long contentLength = Files.size(filePath);

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .contentLength(contentLength)
                .body(resource);
    }

    /**
     * 手动触发文档解析：把文件内容提取为纯文本存入 {@code document.content}，
     * 状态机流转 UPLOADED → PARSING → READY / FAILED。
     *
     * <p><strong>当前同步执行</strong>（教学决策）：请求会阻塞直到解析完成，响应
     * {@code status} 即最终结果。Phase 7 引入 MQ 后本接口语义变为「提交解析任务」，
     * 异步消费解析，届时返回的 {@code status} 为 PARSING，最终结果由后续查询获得。</p>
     *
     * <p><strong>权限：</strong>与上传一致，需知识库 EDITOR/ADMIN 或企业 OWNER/ADMIN；
     * 由 Service 按资源级校验，不加 {@code @PreAuthorize}。</p>
     */
    @PostMapping("/{documentId}/parse")
    public Result<DocumentVO> parseDocument(Authentication authentication,
                                            @PathVariable Long enterpriseId,
                                            @PathVariable Long knowledgeBaseId,
                                            @PathVariable Long documentId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        Document document = documentService.parseDocument(userId, enterpriseId, knowledgeBaseId, documentId);
        return Result.success(DocumentVO.from(document));
    }
}
