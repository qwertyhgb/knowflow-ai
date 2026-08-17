package io.github.qwertyhgb.knowflow.knowledge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRoleMapper;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseMembershipChecker;
import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import io.github.qwertyhgb.knowflow.knowledge.entity.KnowledgeBase;
import io.github.qwertyhgb.knowflow.knowledge.entity.KnowledgeBaseMember;
import io.github.qwertyhgb.knowflow.knowledge.enums.DocumentStatus;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseAccessMode;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseMemberRole;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseStatus;
import io.github.qwertyhgb.knowflow.knowledge.mapper.DocumentMapper;
import io.github.qwertyhgb.knowflow.knowledge.mapper.KnowledgeBaseMapper;
import io.github.qwertyhgb.knowflow.knowledge.vo.DocumentVO;
import io.github.qwertyhgb.knowflow.knowledge.mapper.KnowledgeBaseMemberMapper;
import io.github.qwertyhgb.knowflow.mq.message.DocumentParseMessage;
import io.github.qwertyhgb.knowflow.mq.producer.DocumentParsePublisher;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-17T03:00:00Z");

    @TempDir
    Path tempDir;

    @Mock
    private EnterpriseMapper enterpriseMapper;

    @Mock
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @Mock
    private EnterpriseRoleMapper enterpriseRoleMapper;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private KnowledgeBaseMemberMapper knowledgeBaseMemberMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentParsePublisher parsePublisher;

    private DocumentServiceImpl documentService;

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, EnterpriseMember.class);
        TableInfoHelper.initTableInfo(assistant, EnterpriseRole.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeBase.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseMember.class);
        TableInfoHelper.initTableInfo(assistant, Document.class);
    }

    @BeforeEach
    void setUp() {
        // 激活事务同步上下文：uploadDocument 内部会调用
        // TransactionSynchronizationManager.registerSynchronization 注册 afterCommit 回调，
        // 纯 Mockito 单测默认同步未激活（INACTIVE），会抛 IllegalStateException。
        TransactionSynchronizationManager.initSynchronization();
        EnterpriseMembershipChecker membershipChecker =
                new EnterpriseMembershipChecker(enterpriseMapper, enterpriseMemberMapper);
        documentService = new DocumentServiceImpl(
                documentMapper, knowledgeBaseMapper, knowledgeBaseMemberMapper,
                membershipChecker, enterpriseMemberMapper, enterpriseRoleMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), tempDir.toString(), parsePublisher);
    }

    @AfterEach
    void tearDown() {
        // 清理事务同步上下文，防止用例间残留的同步列表干扰（串扰防护）。
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void shouldUploadDocumentSuccessfully() throws Exception {
        allowEditor();

        byte[] content = "Hello, KnowFlow!".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", content);
        when(documentMapper.exists(any())).thenReturn(false);
        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            doc.setId(100L);
            return 1;
        });

        Document result = documentService.uploadDocument(7L, 10L, 100L, file);

        // 断言哈希正确。
        String expectedHash = sha256Hex(content);
        assertEquals(expectedHash, result.getFileHash(), "SHA-256 哈希应正确");

        // 断言 storageKey 以白名单扩展名结尾。
        assertNotNull(result.getStorageKey());
        assertTrue(result.getStorageKey().endsWith(".pdf"), "storageKey 应以 .pdf 结尾");

        // 断言文件实际落盘。
        Path targetDir = tempDir.resolve("10").resolve("100");
        Path targetFile = targetDir.resolve(result.getStorageKey());
        assertTrue(Files.exists(targetFile), "文件应实际写入磁盘");
        assertArrayEquals(content, Files.readAllBytes(targetFile), "磁盘文件内容应与上传内容一致");

        // 断言记录字段齐全。
        assertEquals(10L, result.getEnterpriseId());
        assertEquals(100L, result.getKnowledgeBaseId());
        assertEquals(7L, result.getUploaderUserId());
        assertEquals("report.pdf", result.getFileName());
        assertEquals(content.length, result.getFileSize());
        assertEquals("application/pdf", result.getContentType());
        assertEquals(DocumentStatus.UPLOADED, result.getStatus());
        assertEquals(NOW, result.getCreatedAt());
        assertEquals(NOW, result.getUpdatedAt());

        verify(documentMapper).insert(any(Document.class));

        // ---- 异步解析消息发布（afterCommit 竞态解法）----
        // 注册的同步在「事务提交时」才执行——纯 Mockito 单测没有真实事务，
        // 手动触发 afterCommit 模拟「事务已提交完成」的那一刻。
        assertFalse(TransactionSynchronizationManager.getSynchronizations().isEmpty(),
                "上传成功应注册事务同步（afterCommit 里发布解析消息）");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        // 断言解析消息已发布且三字段正确：documentId / enterpriseId / knowledgeBaseId。
        ArgumentCaptor<DocumentParseMessage> messageCaptor =
                ArgumentCaptor.forClass(DocumentParseMessage.class);
        verify(parsePublisher).publish(messageCaptor.capture());
        DocumentParseMessage published = messageCaptor.getValue();
        assertEquals(100L, published.documentId());
        assertEquals(10L, published.enterpriseId());
        assertEquals(100L, published.knowledgeBaseId());
    }

    @Test
    void shouldAcceptPdfExtensionCaseInsensitively() throws Exception {
        allowEditor();
        byte[] content = "test".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "Report.PDF", "application/pdf", content);
        when(documentMapper.exists(any())).thenReturn(false);
        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            doc.setId(101L);
            return 1;
        });

        Document result = documentService.uploadDocument(7L, 10L, 100L, file);

        assertTrue(result.getStorageKey().endsWith(".pdf"), "大写 .PDF 应通过白名单校验");
    }

    @Test
    void shouldRejectExeExtension() {
        allowEditor();
        MockMultipartFile file = new MockMultipartFile("file", "virus.exe", "application/octet-stream", "boom".getBytes());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L, file));

        assertEquals(ErrorCode.DOCUMENT_TYPE_NOT_ALLOWED, exception.getErrorCode());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldRejectShExtension() {
        allowEditor();
        MockMultipartFile file = new MockMultipartFile("file", "script.sh", "text/x-shellscript", "run".getBytes());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L, file));

        assertEquals(ErrorCode.DOCUMENT_TYPE_NOT_ALLOWED, exception.getErrorCode());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldUseOnlyFileNameWhenPathTraversalAttempted() throws Exception {
        allowEditor();
        byte[] content = "path traversal test".getBytes();
        // 客户端文件名含路径穿越尝试，存储的文件名应只含最后一段。
        MockMultipartFile file = new MockMultipartFile("file", "../../etc/passwd.txt",
                "text/plain", content);
        when(documentMapper.exists(any())).thenReturn(false);
        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            doc.setId(102L);
            return 1;
        });

        Document result = documentService.uploadDocument(7L, 10L, 100L, file);

        // 存储的文件名只含最后一段 "passwd.txt"，而非路径。
        assertEquals("passwd.txt", result.getFileName(), "文件名不应包含路径");
        // 文件应写入标准目录，而非穿越到上级。
        Path targetDir = tempDir.resolve("10").resolve("100");
        Path targetFile = targetDir.resolve(result.getStorageKey());
        assertTrue(targetFile.startsWith(targetDir), "文件路径应在标准目录内");
    }

    @Test
    void shouldRejectEmptyFile() {
        allowEditor();
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L, emptyFile));

        assertEquals(ErrorCode.DOCUMENT_FILE_EMPTY, exception.getErrorCode());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldRejectNullFile() {
        allowEditor();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L, null));

        assertEquals(ErrorCode.DOCUMENT_FILE_EMPTY, exception.getErrorCode());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldRejectOversizedFile() {
        allowEditor();
        // 构造 10MB + 1 字节的文件。
        byte[] largeContent = new byte[10 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "large.pdf", "application/pdf", largeContent);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L, file));

        assertEquals(ErrorCode.DOCUMENT_TOO_LARGE, exception.getErrorCode());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldRejectDuplicateFile() {
        allowEditor();
        byte[] content = "duplicate".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "dup.pdf", "application/pdf", content);
        when(documentMapper.exists(any())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L, file));

        assertEquals(ErrorCode.DOCUMENT_ALREADY_EXISTS, exception.getErrorCode());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldRejectForViewerMember() {
        allowMemberWithRole(KnowledgeBaseMemberRole.VIEWER);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L,
                        new MockMultipartFile("file", "test.pdf", "application/pdf", "data".getBytes())));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldRejectForNonMember() {
        allowNonMember();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L,
                        new MockMultipartFile("file", "test.pdf", "application/pdf", "data".getBytes())));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldAllowEnterpriseAdminWithoutKbMembership() throws Exception {
        allowEnterpriseAdmin();
        byte[] content = "admin upload".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "admin.pdf", "application/pdf", content);
        when(documentMapper.exists(any())).thenReturn(false);
        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            doc.setId(200L);
            return 1;
        });

        Document result = documentService.uploadDocument(7L, 10L, 100L, file);

        assertEquals(200L, result.getId());
        verify(documentMapper).insert(any(Document.class));
    }

    @Test
    void shouldRejectWhenKnowledgeBaseNotFound() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L,
                        new MockMultipartFile("file", "test.pdf", "application/pdf", "data".getBytes())));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, exception.getErrorCode());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldRejectWhenKnowledgeBaseDisabled() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
        KnowledgeBase disabled = kb(100L);
        disabled.setStatus(KnowledgeBaseStatus.DISABLED);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(disabled);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L,
                        new MockMultipartFile("file", "test.pdf", "application/pdf", "data".getBytes())));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, exception.getErrorCode());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldRejectForNonEnterpriseMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L,
                        new MockMultipartFile("file", "test.pdf", "application/pdf", "data".getBytes())));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldDeleteFileWhenInsertFails() throws Exception {
        allowEditor();
        byte[] content = "rollback test".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "rollback.pdf", "application/pdf", content);
        when(documentMapper.exists(any())).thenReturn(false);
        // insert 抛出异常模拟数据库故障。
        when(documentMapper.insert(any(Document.class))).thenThrow(new RuntimeException("DB error"));

        assertThrows(RuntimeException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L, file));

        // 断言目标文件已被删除。
        Path targetDir = tempDir.resolve("10").resolve("100");
        boolean fileExists = Files.list(targetDir).findAny().isPresent();
        assertFalse(fileExists, "插入失败后应清理已写磁盘文件");
    }

    @Test
    void shouldNotPublishWhenUploadInsertFails() {
        allowEditor();
        byte[] content = "no-publish".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "fail.pdf", "application/pdf", content);
        when(documentMapper.exists(any())).thenReturn(false);
        when(documentMapper.insert(any(Document.class))).thenThrow(new RuntimeException("DB error"));

        assertThrows(RuntimeException.class,
                () -> documentService.uploadDocument(7L, 10L, 100L, file));

        // 插入失败：registerSynchronization 的代码路径未执行，解析消息绝不发布。
        verify(parsePublisher, never()).publish(any(DocumentParseMessage.class));
    }

    @Test
    void shouldPublishFailureNotBreakUpload() throws Exception {
        allowEditor();
        byte[] content = "graceful".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "graceful.pdf", "application/pdf", content);
        when(documentMapper.exists(any())).thenReturn(false);
        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            doc.setId(300L);
            return 1;
        });
        // 发布失败（如 broker 短暂不可用）：上传本身必须不受影响（优雅降级）。
        doThrow(new RuntimeException("broker down"))
                .when(parsePublisher).publish(any(DocumentParseMessage.class));

        Document result = documentService.uploadDocument(7L, 10L, 100L, file);

        // 上传成功、状态 UPLOADED、记录返回正常。
        assertEquals(DocumentStatus.UPLOADED, result.getStatus());
        assertEquals(300L, result.getId());

        // 手动触发 afterCommit 模拟事务已提交：发布抛异常被吞掉，不向外传播——
        // 这正是「发布失败不阻塞上传」的优雅降级逻辑（手动 parse 接口作为补偿）。
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit));
    }

    // ==================== 文档列表测试 ====================

    @Test
    void shouldListDocumentsWhenMember() {
        allowMember();
        Document doc1 = document(200L, "report.pdf", "application/pdf", 1024L);
        Document doc2 = document(201L, "guide.txt", "text/plain", 512L);
        when(documentMapper.selectList(any())).thenReturn(List.of(doc1, doc2));

        List<DocumentVO> result = documentService.listDocuments(7L, 10L, 100L);

        assertEquals(2, result.size());
        assertEquals(200L, result.get(0).getId());
        assertEquals("report.pdf", result.get(0).getFileName());
        assertEquals(201L, result.get(1).getId());
        assertEquals("guide.txt", result.get(1).getFileName());
        // 验证查询条件包含 enterpriseId + knowledgeBaseId 及排序。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Document>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(documentMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("enterprise_id"), "列表查询必须限定当前企业");
        assertTrue(sql.contains("knowledge_base_id"), "列表查询必须限定当前知识库");
        assertTrue(sql.contains("created_at"), "列表查询应按 created_at 倒序");
    }

    @Test
    void shouldListDocumentsWhenPublicNonMember() {
        allowPublicNonMember();
        Document doc = document(200L, "public.pdf", "application/pdf", 1024L);
        when(documentMapper.selectList(any())).thenReturn(List.of(doc));

        List<DocumentVO> result = documentService.listDocuments(7L, 10L, 100L);

        assertEquals(1, result.size());
        assertEquals(200L, result.get(0).getId());
    }

    @Test
    void shouldThrowNotFoundWhenPrivateNonMember() {
        allowPrivateNonMember();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.listDocuments(7L, 10L, 100L));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(documentMapper);
    }

    @Test
    void shouldThrowNotFoundWhenKnowledgeBaseNotFound() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.listDocuments(7L, 10L, 100L));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(documentMapper);
    }

    @Test
    void shouldThrowNotFoundWhenKnowledgeBaseDisabled() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
        KnowledgeBase disabled = kb(100L);
        disabled.setStatus(KnowledgeBaseStatus.DISABLED);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(disabled);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.listDocuments(7L, 10L, 100L));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(documentMapper);
    }

    @Test
    void shouldThrowForbiddenWhenNotEnterpriseMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.listDocuments(7L, 10L, 100L));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verifyNoInteractions(documentMapper);
    }

    @Test
    void shouldReturnEmptyListWhenNoDocuments() {
        allowMember();
        when(documentMapper.selectList(any())).thenReturn(List.of());

        List<DocumentVO> result = documentService.listDocuments(7L, 10L, 100L);

        assertTrue(result.isEmpty());
        verify(documentMapper).selectList(any());
    }

    // ==================== 文档下载测试 ====================

    @Test
    void shouldGetDocumentForDownload() throws Exception {
        allowMember();
        Document doc = document(200L, "report.pdf", "application/pdf", 1024L);
        doc.setStorageKey("download-test.pdf");
        when(documentMapper.selectOne(any())).thenReturn(doc);
        // 在 tempDir 中创建真实的磁盘文件。
        Path targetDir = tempDir.resolve("10").resolve("100");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("download-test.pdf"), "download content");

        Document result = documentService.getDocumentForDownload(7L, 10L, 100L, 200L);

        assertEquals(200L, result.getId());
        assertEquals("report.pdf", result.getFileName());
        // 验证查询条件包含三条件（id + enterpriseId + knowledgeBaseId）。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Document>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(documentMapper).selectOne(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        // 三条件防越权：id、enterprise_id、knowledge_base_id 都应出现在查询条件中。
        assertTrue(sql.contains("id"), "查询应包含 id 条件");
        assertTrue(sql.contains("enterprise_id"), "查询应包含 enterprise_id 条件");
        assertTrue(sql.contains("knowledge_base_id"), "查询应包含 knowledge_base_id 条件");
    }

    @Test
    void shouldThrowDocumentNotFoundWhenDocumentNotExists() {
        allowMember();
        when(documentMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.getDocumentForDownload(7L, 10L, 100L, 999L));

        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void shouldThrowFileMissingWhenDiskFileNotFound() {
        allowMember();
        Document doc = document(200L, "missing.pdf", "application/pdf", 1024L);
        doc.setStorageKey("nonexistent.pdf");
        when(documentMapper.selectOne(any())).thenReturn(doc);
        // 不在 tempDir 中创建文件 → 磁盘缺失。

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.getDocumentForDownload(7L, 10L, 100L, 200L));

        assertEquals(ErrorCode.DOCUMENT_FILE_MISSING, exception.getErrorCode());
    }

    @Test
    void shouldThrowNotFoundWhenCrossKnowledgeBaseId() {
        allowMember();
        // 即使文档 id = 200L 存在，但 knowledgeBaseId 不匹配 → selectOne 返回 null。
        when(documentMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.getDocumentForDownload(7L, 10L, 100L, 200L));

        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, exception.getErrorCode());
        // 验证查询包含三条件。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Document>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(documentMapper).selectOne(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("id"));
        assertTrue(sql.contains("enterprise_id"));
        assertTrue(sql.contains("knowledge_base_id"));
    }

    // ==================== 文档解析测试 ====================
    //
    // 解析测试把「真实文件写入 @TempDir + 固定钟偏移」组合起来验证：
    // parseDocument 会读取 localDir 下的真实文件（TXT/MD/PDF/DOCX 互不相同的解析路径），
    // 并用 Clock 生成 updatedAt。基座钟是 Clock.fixed(NOW)，这里用 Clock.offset 派生
    // 「比 NOW 晚 1 秒」的钟，使 updatedAt 的变更可被观察到。

    /** 固定钟偏移 +1s 的解析用服务实例：updatedAt 从 NOW 前进到 NOW+1s，便于断言「已更新」。 */
    private DocumentServiceImpl parseServiceWithAdvancedClock() {
        Clock advancedClock = Clock.offset(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1));
        EnterpriseMembershipChecker membershipChecker =
                new EnterpriseMembershipChecker(enterpriseMapper, enterpriseMemberMapper);
        return new DocumentServiceImpl(
                documentMapper, knowledgeBaseMapper, knowledgeBaseMemberMapper,
                membershipChecker, enterpriseMemberMapper, enterpriseRoleMapper,
                advancedClock, tempDir.toString(), parsePublisher);
    }

    @Test
    void shouldParseTxtDocumentSuccessfully() throws Exception {
        allowEditor();
        // 在 @TempDir 中写入真实 TXT 文件：localDir/10/100/note.txt。
        Path targetDir = tempDir.resolve("10").resolve("100");
        Files.createDirectories(targetDir);
        String expectedContent = "Hello KnowFlow 你好\n第二行";
        Files.writeString(targetDir.resolve("note.txt"), expectedContent, StandardCharsets.UTF_8);

        Document doc = document(200L, "note.txt", "text/plain", (long) expectedContent.length());
        doc.setStorageKey("note.txt");
        when(documentMapper.selectOne(any())).thenReturn(doc);

        DocumentServiceImpl service = parseServiceWithAdvancedClock();
        Document result = service.parseDocument(7L, 10L, 100L, 200L);

        // 状态机流转到 READY；content 与原文一致；failed_reason 为 null。
        assertEquals(DocumentStatus.READY, result.getStatus());
        assertEquals(expectedContent, result.getContent(), "TXT 解析结果应与原文一致");
        assertNull(result.getFailedReason());
        // updatedAt 由固定钟驱动前进 1 秒：断言「时间确实被更新」而非停留在原值。
        assertEquals(NOW.plusSeconds(1), result.getUpdatedAt());
        // 两次 updateById：第一次标记 PARSING，第二次落 READY。
        verify(documentMapper, times(2)).updateById(any(Document.class));
    }

    @Test
    void shouldParseMdDocumentSuccessfully() throws Exception {
        allowEditor();
        // MD 与 TXT 走同一解析路径（直接读字节转 UTF-8 字符串），单独用例防回归拆分。
        Path targetDir = tempDir.resolve("10").resolve("100");
        Files.createDirectories(targetDir);
        String expectedContent = "# KnowFlow 使用手册\n- 上传文档\n- 触发解析";
        Files.writeString(targetDir.resolve("guide.md"), expectedContent, StandardCharsets.UTF_8);

        Document doc = document(201L, "guide.md", "text/markdown", (long) expectedContent.length());
        doc.setStorageKey("guide.md");
        when(documentMapper.selectOne(any())).thenReturn(doc);

        Document result = parseServiceWithAdvancedClock().parseDocument(7L, 10L, 100L, 201L);

        assertEquals(DocumentStatus.READY, result.getStatus());
        assertEquals(expectedContent, result.getContent(), "MD 解析结果应与原文一致");
        assertNull(result.getFailedReason());
    }

    @Test
    void shouldParsePdfDocumentSuccessfully() throws Exception {
        allowEditor();
        // 用 PDFBox 在 @TempDir 生成一个含已知文本的小 PDF（顺带验证引库真正可用）。
        Path targetDir = tempDir.resolve("10").resolve("100");
        Files.createDirectories(targetDir);
        String expectedText = "KnowFlow PDF parsing test 2026";
        createPdf(targetDir.resolve("sample.pdf"), expectedText);

        Document doc = document(202L, "sample.pdf", "application/pdf", 2048L);
        doc.setStorageKey("sample.pdf");
        when(documentMapper.selectOne(any())).thenReturn(doc);

        Document result = parseServiceWithAdvancedClock().parseDocument(7L, 10L, 100L, 202L);

        assertEquals(DocumentStatus.READY, result.getStatus());
        assertNotNull(result.getContent(), "PDF 应提取出文本");
        assertTrue(result.getContent().contains("KnowFlow PDF parsing test"),
                "PDF 提取文本应包含写入页面的内容，实际: " + result.getContent());
        assertNull(result.getFailedReason());
    }

    @Test
    void shouldParseDocxDocumentSuccessfully() throws Exception {
        allowEditor();
        // 用 POI 在 @TempDir 生成含已知文本的 docx（顺带验证 POI 引库真正可用）。
        Path targetDir = tempDir.resolve("10").resolve("100");
        Files.createDirectories(targetDir);
        String expectedText = "KnowFlow DOCX parsing test";
        createDocx(targetDir.resolve("sample.docx"), expectedText);

        Document doc = document(203L, "sample.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 2048L);
        doc.setStorageKey("sample.docx");
        when(documentMapper.selectOne(any())).thenReturn(doc);

        Document result = parseServiceWithAdvancedClock().parseDocument(7L, 10L, 100L, 203L);

        assertEquals(DocumentStatus.READY, result.getStatus());
        assertNotNull(result.getContent(), "DOCX 应提取出文本");
        assertTrue(result.getContent().contains("KnowFlow DOCX parsing test"),
                "DOCX 提取文本应包含写入的文本，实际: " + result.getContent());
        assertNull(result.getFailedReason());
    }

    @Test
    void shouldMarkParsingFailedWhenPdfContentInvalid() throws Exception {
        allowEditor();
        // 扩展名是 .pdf 但内容是乱字节：PDFBox 无法解析 → FAILED 终态，不抛异常。
        Path targetDir = tempDir.resolve("10").resolve("100");
        Files.createDirectories(targetDir);
        Files.write(targetDir.resolve("fake.pdf"),
                new byte[]{(byte) 0xC0, (byte) 0xFF, 0x01, (byte) 0xFE, 0x00, 0x1B});

        Document doc = document(204L, "fake.pdf", "application/pdf", 6L);
        doc.setStorageKey("fake.pdf");
        when(documentMapper.selectOne(any())).thenReturn(doc);

        Document result = parseServiceWithAdvancedClock().parseDocument(7L, 10L, 100L, 204L);

        // 解析失败是正常终态：不抛异常、status=FAILED、failed_reason 非空（白名单短语）、content 为空。
        assertEquals(DocumentStatus.FAILED, result.getStatus());
        assertNotNull(result.getFailedReason(), "解析失败应记录白名单短语");
        assertTrue(result.getFailedReason().contains("解析失败"), "failed_reason 应为固定短语: " + result.getFailedReason());
        assertNull(result.getContent());
    }

    @Test
    void shouldPersistParsingStatusBeforeExtraction() throws Exception {
        allowEditor();
        Path targetDir = tempDir.resolve("10").resolve("100");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("note.txt"), "content");

        Document doc = document(205L, "note.txt", "text/plain", 7L);
        doc.setStorageKey("note.txt");
        when(documentMapper.selectOne(any())).thenReturn(doc);

        // 逐次调用快照状态：updateById 两次传入的是同一个 Document 实例（先在原对象上置
        // PARSING 落库，再置 READY 落库），ArgumentCaptor 只持有引用、捕获后值已被覆盖，
        // 无法区分两次状态。这里在每次调用发生的「当时」拷贝 status/updatedAt 作快照。
        List<Document> updateSnapshots = new ArrayList<>();
        when(documentMapper.updateById(any(Document.class))).thenAnswer(invocation -> {
            Document arg = invocation.getArgument(0);
            Document snapshot = new Document();
            snapshot.setStatus(arg.getStatus());
            snapshot.setUpdatedAt(arg.getUpdatedAt());
            updateSnapshots.add(snapshot);
            return 1;
        });

        parseServiceWithAdvancedClock().parseDocument(7L, 10L, 100L, 205L);

        verify(documentMapper, times(2)).updateById(any(Document.class));
        assertEquals(2, updateSnapshots.size());
        // 第一次 update 必须先把状态落库为 PARSING（提前标记防并发重复解析），第二次才是 READY。
        assertEquals(DocumentStatus.PARSING, updateSnapshots.get(0).getStatus(),
                "第一次 update 应标记 PARSING");
        assertEquals(NOW.plusSeconds(1), updateSnapshots.get(0).getUpdatedAt(),
                "PARSING 落库应携带新的 updatedAt");
        assertEquals(DocumentStatus.READY, updateSnapshots.get(1).getStatus(),
                "第二次 update 应为 READY");
    }

    @Test
    void shouldRejectParseWhenDocumentAlreadyReady() {
        allowEditor();
        Document doc = document(206L, "ready.pdf", "application/pdf", 100L);
        doc.setStatus(DocumentStatus.READY);
        when(documentMapper.selectOne(any())).thenReturn(doc);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.parseDocument(7L, 10L, 100L, 206L));

        assertEquals(ErrorCode.DOCUMENT_STATUS_NOT_ALLOWED, exception.getErrorCode());
        verify(documentMapper, never()).updateById(any(Document.class));
    }

    @Test
    void shouldRejectParseWhenDocumentAlreadyFailed() {
        allowEditor();
        Document doc = document(207L, "failed.pdf", "application/pdf", 100L);
        doc.setStatus(DocumentStatus.FAILED);
        when(documentMapper.selectOne(any())).thenReturn(doc);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.parseDocument(7L, 10L, 100L, 207L));

        assertEquals(ErrorCode.DOCUMENT_STATUS_NOT_ALLOWED, exception.getErrorCode());
        verify(documentMapper, never()).updateById(any(Document.class));
    }

    @Test
    void shouldRejectParseForViewerMember() {
        allowMemberWithRole(KnowledgeBaseMemberRole.VIEWER);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.parseDocument(7L, 10L, 100L, 200L));

        // VIEWER 只读：权限校验失败，先于任何文档定位与状态更新。
        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verifyNoInteractions(documentMapper);
    }

    @Test
    void shouldRejectParseForNonKbMember() {
        allowNonMember();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.parseDocument(7L, 10L, 100L, 200L));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verifyNoInteractions(documentMapper);
    }

    @Test
    void shouldThrowDocumentNotFoundWhenParseTargetMissing() {
        allowEditor();
        when(documentMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.parseDocument(7L, 10L, 100L, 999L));

        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, exception.getErrorCode());
        verify(documentMapper, never()).updateById(any(Document.class));
    }

    @Test
    void shouldThrowFileMissingWhenParseDiskFileNotFound() {
        allowEditor();
        // 记录存在但磁盘文件缺失（storageKey 指向不存在的文件）→ 存储层异常 404，
        // 并且不应先落库 PARSING（避免把文档留在无法恢复的瞬时态）。
        Document doc = document(208L, "missing.pdf", "application/pdf", 100L);
        doc.setStorageKey("nonexistent.pdf");
        when(documentMapper.selectOne(any())).thenReturn(doc);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.parseDocument(7L, 10L, 100L, 208L));

        assertEquals(ErrorCode.DOCUMENT_FILE_MISSING, exception.getErrorCode());
        verify(documentMapper, never()).updateById(any(Document.class));
    }

    // -------------------- parseDocumentInternal（无权限内部入口） --------------------
    // 内部方法不做权限校验：以下用例不调用 allowEditor()/allowActiveMember() 等权限桩，
    // 直接验证状态机与解析核心逻辑，与 parseDocument 的成功用例共享同一套断言。

    @Test
    void shouldParseInternalWithoutPermissionCheck() throws Exception {
        // 不设任何权限桩：证明 parseDocumentInternal 不依赖权限校验即可完成解析。
        Path targetDir = tempDir.resolve("10").resolve("100");
        Files.createDirectories(targetDir);
        String expectedContent = "internal parse content";
        Files.writeString(targetDir.resolve("internal.txt"), expectedContent, StandardCharsets.UTF_8);

        Document doc = document(300L, "internal.txt", "text/plain", (long) expectedContent.length());
        doc.setStorageKey("internal.txt");
        when(documentMapper.selectOne(any())).thenReturn(doc);

        Document result = parseServiceWithAdvancedClock()
                .parseDocumentInternal(10L, 100L, 300L);

        // 与 parseDocument 成功用例一致：流转 READY、content 正确、无失败原因。
        assertEquals(DocumentStatus.READY, result.getStatus());
        assertEquals(expectedContent, result.getContent(), "内部解析结果应与原文一致");
        assertNull(result.getFailedReason());
        verify(documentMapper, times(2)).updateById(any(Document.class));
    }

    @Test
    void shouldRejectInternalParseWhenDocumentAlreadyReady() {
        // 同状态机校验：重复消息场景（第二次消费时文档已 READY）→ 抛 DOCUMENT_STATUS_NOT_ALLOWED。
        Document doc = document(301L, "ready.txt", "text/plain", 10L);
        doc.setStatus(DocumentStatus.READY);
        when(documentMapper.selectOne(any())).thenReturn(doc);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> documentService.parseDocumentInternal(10L, 100L, 301L));

        assertEquals(ErrorCode.DOCUMENT_STATUS_NOT_ALLOWED, exception.getErrorCode());
        verify(documentMapper, never()).updateById(any(Document.class));
    }

    /** 用 PDFBox 3.x API 生成一页含指定文本的 PDF（Standard14 字体 + PDPageContentStream）。 */
    private static void createPdf(Path file, String text) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(pdf, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText(text);
                contentStream.endText();
            }
            pdf.save(file.toFile());
        }
    }

    /** 用 POI 生成一个含指定文本的 docx（默认模板 + 段落）。 */
    private static void createDocx(Path file, String text) throws IOException {
        try (XWPFDocument docx = new XWPFDocument()) {
            XWPFParagraph paragraph = docx.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(text);
            try (OutputStream out = Files.newOutputStream(file)) {
                docx.write(out);
            }
        }
    }

    // ==================== 辅助方法 ====================

    /** 操作者是知识库成员（VIEWER）——列表/下载可见。 */
    private void allowMember() {
        allowActiveMember();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb(100L));
        KnowledgeBaseMember member = new KnowledgeBaseMember();
        member.setMemberRole(KnowledgeBaseMemberRole.VIEWER);
        when(knowledgeBaseMemberMapper.selectOne(any())).thenReturn(member);
    }

    /** 操作者是企业正常成员，知识库为 PUBLIC 但非成员。 */
    private void allowPublicNonMember() {
        allowActiveMember();
        KnowledgeBase publicKb = kb(100L);
        publicKb.setAccessMode(KnowledgeBaseAccessMode.PUBLIC);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(publicKb);
        when(knowledgeBaseMemberMapper.selectOne(any())).thenReturn(null);
    }

    /** 操作者是企业正常成员，知识库为 PRIVATE 且非成员。 */
    private void allowPrivateNonMember() {
        allowActiveMember();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb(100L));
        when(knowledgeBaseMemberMapper.selectOne(any())).thenReturn(null);
    }

    private Document document(Long id, String fileName, String contentType, Long fileSize) {
        Document doc = new Document();
        doc.setId(id);
        doc.setEnterpriseId(10L);
        doc.setKnowledgeBaseId(100L);
        doc.setUploaderUserId(7L);
        doc.setFileName(fileName);
        doc.setFileSize(fileSize);
        doc.setContentType(contentType);
        doc.setFileHash("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890");
        doc.setStorageKey("uuid.pdf");
        doc.setStatus(DocumentStatus.UPLOADED);
        doc.setCreatedAt(NOW);
        doc.setUpdatedAt(NOW);
        return doc;
    }

    /** 操作者是知识库 EDITOR 成员。 */
    private void allowEditor() {
        allowActiveMember();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb(100L));
        KnowledgeBaseMember member = new KnowledgeBaseMember();
        member.setMemberRole(KnowledgeBaseMemberRole.EDITOR);
        when(knowledgeBaseMemberMapper.selectOne(any())).thenReturn(member);
    }

    /** 操作者是知识库指定角色的成员。 */
    private void allowMemberWithRole(KnowledgeBaseMemberRole role) {
        allowActiveMember();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb(100L));
        KnowledgeBaseMember member = new KnowledgeBaseMember();
        member.setMemberRole(role);
        when(knowledgeBaseMemberMapper.selectOne(any())).thenReturn(member);
    }

    /** 操作者是企业正常成员但非知识库成员。 */
    private void allowNonMember() {
        allowActiveMember();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb(100L));
        when(knowledgeBaseMemberMapper.selectOne(any())).thenReturn(null);
    }

    /** 操作者是企业 OWNER/ADMIN 但非知识库成员。 */
    private void allowEnterpriseAdmin() {
        allowActiveMember();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb(100L));
        when(knowledgeBaseMemberMapper.selectOne(any())).thenReturn(null);
        EnterpriseMember em = new EnterpriseMember();
        em.setEnterpriseId(10L);
        em.setUserId(7L);
        em.setRoleId(200L);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(em);
        EnterpriseRole role = new EnterpriseRole();
        role.setId(200L);
        role.setEnterpriseId(10L);
        role.setCode("OWNER");
        role.setStatus(EnterpriseRoleStatus.NORMAL);
        when(enterpriseRoleMapper.selectById(200L)).thenReturn(role);
    }

    private void allowActiveMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
    }

    private KnowledgeBase kb(Long id) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setEnterpriseId(10L);
        kb.setStatus(KnowledgeBaseStatus.NORMAL);
        kb.setAccessMode(KnowledgeBaseAccessMode.PRIVATE);
        return kb;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** assertEquals for byte[] — JUnit 5.11+ has assertArrayEquals. */
    private static void assertArrayEquals(byte[] expected, byte[] actual, String message) {
        if (expected.length != actual.length) {
            throw new AssertionError(message + ": length mismatch " + expected.length + " vs " + actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                throw new AssertionError(message + ": byte mismatch at index " + i);
            }
        }
    }
}