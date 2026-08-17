package io.github.qwertyhgb.knowflow.knowledge.controller;

import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRoleMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRolePermissionMapper;
import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import io.github.qwertyhgb.knowflow.knowledge.enums.DocumentStatus;
import io.github.qwertyhgb.knowflow.knowledge.vo.DocumentVO;
import io.github.qwertyhgb.knowflow.knowledge.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档上传接口的 Web 层测试：认证、企业上下文、成员校验、白名单、JSON 格式与统一响应结构。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService documentService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @MockitoBean
    private EnterpriseRoleMapper enterpriseRoleMapper;

    @MockitoBean
    private EnterpriseRolePermissionMapper enterpriseRolePermissionMapper;

    @BeforeEach
    void stubEnterpriseContext() {
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(10L);
        member.setUserId(7L);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        member.setRoleId(100L);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member);
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role("OWNER"));
        when(enterpriseRolePermissionMapper.selectPermissionCodesByRoleId(any()))
                .thenReturn(allPermissionCodes());
    }

    @Test
    void shouldUploadDocumentWithValidFile() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(documentService.uploadDocument(eq(7L), eq(10L), eq(100L), any()))
                .thenReturn(document(200L, "report.pdf", "application/pdf", 1024L, "uuid.pdf"));

        MockMultipartFile file = new MockMultipartFile("file", "report.pdf",
                "application/pdf", "test content".getBytes());

        mockMvc.perform(multipart("/api/enterprises/10/knowledge-bases/100/documents")
                        .file(file)
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(200))
                .andExpect(jsonPath("$.data.fileName").value("report.pdf"))
                .andExpect(jsonPath("$.data.fileSize").value(1024))
                .andExpect(jsonPath("$.data.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-08-17T03:00:00.123Z"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-08-17T03:00:00.123Z"));

        verify(documentService).uploadDocument(eq(7L), eq(10L), eq(100L), any());
    }

    @Test
    void shouldRejectUnsupportedFileType() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.DOCUMENT_TYPE_NOT_ALLOWED))
                .when(documentService).uploadDocument(eq(7L), eq(10L), eq(100L), any());

        MockMultipartFile file = new MockMultipartFile("file", "virus.exe",
                "application/octet-stream", "boom".getBytes());

        mockMvc.perform(multipart("/api/enterprises/10/knowledge-bases/100/documents")
                        .file(file)
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_TYPE_NOT_ALLOWED"));
    }

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf",
                "application/pdf", "data".getBytes());

        mockMvc.perform(multipart("/api/enterprises/10/knowledge-bases/100/documents")
                        .file(file)
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(documentService);
    }

    @Test
    void shouldRejectMissingEnterpriseContext() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf",
                "application/pdf", "data".getBytes());

        mockMvc.perform(multipart("/api/enterprises/10/knowledge-bases/100/documents")
                        .file(file)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISSING"));

        verifyNoInteractions(documentService);
    }

    @Test
    void shouldReturnForbiddenForNonMember() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        // 覆盖共享桩：EnterpriseContextFilter 查询不到该企业成员 → 403。
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(null);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf",
                "application/pdf", "data".getBytes());

        mockMvc.perform(multipart("/api/enterprises/10/knowledge-bases/100/documents")
                        .file(file)
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(documentService);
    }

    @Test
    void shouldReturnForbiddenWhenServiceThrowsForbidden() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(documentService).uploadDocument(eq(7L), eq(10L), eq(100L), any());

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf",
                "application/pdf", "data".getBytes());

        mockMvc.perform(multipart("/api/enterprises/10/knowledge-bases/100/documents")
                        .file(file)
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ==================== 文档列表测试 ====================

    @Test
    void shouldListDocumentsSuccessfully() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        DocumentVO doc1 = documentVO(200L, "report.pdf", "application/pdf", 1024L);
        DocumentVO doc2 = documentVO(201L, "guide.txt", "text/plain", 512L);
        when(documentService.listDocuments(7L, 10L, 100L)).thenReturn(List.of(doc1, doc2));

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/100/documents")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(200))
                .andExpect(jsonPath("$.data[0].fileName").value("report.pdf"))
                .andExpect(jsonPath("$.data[0].fileSize").value(1024))
                .andExpect(jsonPath("$.data[1].id").value(201))
                .andExpect(jsonPath("$.data[1].fileName").value("guide.txt"));

        verify(documentService).listDocuments(7L, 10L, 100L);
    }

    @Test
    void shouldReturnEmptyListWhenNoDocuments() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(documentService.listDocuments(7L, 10L, 100L)).thenReturn(List.of());

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/100/documents")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void shouldListDocumentsReturnServiceError() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND))
                .when(documentService).listDocuments(7L, 10L, 100L);

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/100/documents")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));
    }

    // ==================== 文档下载测试 ====================

    @Test
    void shouldDownloadDocumentSuccessfully() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        // 在 Spring Boot 配置的 knowflow.storage.local-dir 默认路径下创建真实文件用于下载。
        Path downloadDir = Path.of("./data/files/10/100");
        Files.createDirectories(downloadDir);
        byte[] fileContent = "Hello, download!".getBytes(StandardCharsets.UTF_8);
        Files.write(downloadDir.resolve("uuid.pdf"), fileContent);

        Document downloadDoc = document(200L, "中文报告.pdf", "application/pdf", 1024L, "uuid.pdf");
        when(documentService.getDocumentForDownload(7L, 10L, 100L, 200L)).thenReturn(downloadDoc);

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/100/documents/200/download")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                // 断言 Content-Type 正确。
                .andExpect(header().string("Content-Type", "application/pdf"))
                // 断言 Content-Disposition 使用 RFC 5987 编码中文文件名。
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87%E6%8A%A5%E5%91%8A.pdf"))
                // 断言 Content-Length 与实际文件大小一致。
                .andExpect(header().longValue("Content-Length", fileContent.length))
                // 断言响应体字节与源文件一致。
                .andExpect(content().bytes(fileContent));
    }

    @Test
    void shouldReturnDocumentNotFoundWhenDownloadNotExists() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND))
                .when(documentService).getDocumentForDownload(7L, 10L, 100L, 999L);

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/100/documents/999/download")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void shouldReturnUnauthorizedWhenDownloadWithoutToken() throws Exception {
        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/100/documents/200/download")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(documentService);
    }

    @Test
    void shouldRejectDownloadMissingEnterpriseContext() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/100/documents/200/download")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISSING"));

        verifyNoInteractions(documentService);
    }

    @Test
    void shouldReturnNotFoundWhenPrivateKnowledgeBaseForDownload() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND))
                .when(documentService).getDocumentForDownload(7L, 10L, 100L, 200L);

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/100/documents/200/download")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));
    }

    // ==================== 文档解析测试 ====================

    @Test
    void shouldParseDocumentSuccessfully() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        // 解析成功：status 流转到 READY，content 写入（VO 中 status 枚举名序列化）。
        Document parsed = document(200L, "report.pdf", "application/pdf", 1024L, "uuid.pdf");
        parsed.setStatus(DocumentStatus.READY);
        parsed.setContent("parsed text");
        when(documentService.parseDocument(7L, 10L, 100L, 200L)).thenReturn(parsed);

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases/100/documents/200/parse")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(200))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-08-17T03:00:00.123Z"));

        verify(documentService).parseDocument(7L, 10L, 100L, 200L);
    }

    @Test
    void shouldReturnConflictWhenParseStatusNotAllowed() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.DOCUMENT_STATUS_NOT_ALLOWED))
                .when(documentService).parseDocument(7L, 10L, 100L, 200L);

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases/100/documents/200/parse")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_STATUS_NOT_ALLOWED"));
    }

    @Test
    void shouldReturnNotFoundWhenParseDocumentMissing() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND))
                .when(documentService).parseDocument(7L, 10L, 100L, 999L);

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases/100/documents/999/parse")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void shouldReturnForbiddenWhenParseWithoutEditorPermission() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(documentService).parseDocument(7L, 10L, 100L, 200L);

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases/100/documents/200/parse")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnUnauthorizedWhenParseWithoutToken() throws Exception {
        mockMvc.perform(post("/api/enterprises/10/knowledge-bases/100/documents/200/parse")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(documentService);
    }

    // ==================== 辅助方法 ====================

    private Document document(Long id, String fileName, String contentType, Long fileSize, String storageKey) {
        Document document = new Document();
        document.setId(id);
        document.setKnowledgeBaseId(100L);
        document.setEnterpriseId(10L);
        document.setUploaderUserId(7L);
        document.setFileName(fileName);
        document.setFileSize(fileSize);
        document.setContentType(contentType);
        document.setFileHash("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890");
        document.setStorageKey(storageKey);
        document.setStatus(DocumentStatus.UPLOADED);
        document.setCreatedAt(Instant.parse("2026-08-17T03:00:00.123987654Z"));
        document.setUpdatedAt(Instant.parse("2026-08-17T03:00:00.123987654Z"));
        return document;
    }

    private DocumentVO documentVO(Long id, String fileName, String contentType, Long fileSize) {
        // 用 DocumentVO.from() 来构造，但需要先有 Document 实体。
        Document doc = document(id, fileName, contentType, fileSize, "uuid.pdf");
        return DocumentVO.from(doc);
    }

    private List<String> allPermissionCodes() {
        return List.of(
                "enterprise:update", "member:view", "member:remove", "member:status",
                "invitation:create", "invitation:list", "invitation:revoke",
                "department:create", "department:update", "department:delete", "department:status");
    }

    private EnterpriseRole role(String code) {
        EnterpriseRole role = new EnterpriseRole();
        role.setId(100L);
        role.setEnterpriseId(10L);
        role.setCode(code);
        role.setName(code);
        role.setStatus(EnterpriseRoleStatus.NORMAL);
        return role;
    }
}