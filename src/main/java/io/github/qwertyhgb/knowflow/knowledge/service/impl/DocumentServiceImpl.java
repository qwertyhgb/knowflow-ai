package io.github.qwertyhgb.knowflow.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
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
import io.github.qwertyhgb.knowflow.knowledge.mapper.KnowledgeBaseMemberMapper;
import io.github.qwertyhgb.knowflow.knowledge.service.DocumentService;
import io.github.qwertyhgb.knowflow.knowledge.vo.DocumentVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;

/**
 * 文档业务服务实现。
 *
 * <p><strong>上传流程（教学点居中）：</strong></p>
 * <ol>
 *   <li>资源权限校验（{@code requireKnowledgeBaseEditor}）；</li>
 *   <li>文件校验：非空、扩展名白名单、大小上限；</li>
 *   <li>SHA-256 内容哈希（企业维度去重）；</li>
 *   <li>写入本地磁盘（事务<strong>外</strong>——磁盘操作无法随事务回滚）；</li>
 *   <li>数据库记录插入（事务内）；插入失败时手动清理已写磁盘文件。</li>
 * </ol>
 *
 * <p><strong>解析流程（parseDocument，教学点居中）：</strong></p>
 * <ol>
 *   <li>权限校验与上传一致；三条件定位文档；仅 UPLOADED 可解析（409 防重复解析）；</li>
 *   <li>提前落库标记 PARSING（解析可能耗时，先标记防并发重复解析）；</li>
 *   <li>按扩展名分发提取纯文本：TXT/MD 直接读字节、PDF 用 PDFBox、DOCX 用 POI；</li>
 *   <li>成功 → READY + content；失败 → FAILED + 白名单短语（正常终态，不抛异常）。</li>
 * </ol>
 *
 * <p>权限模型与知识库模块一致：两级编辑者取或——资源级（知识库成员且 memberRole ∈
 * {EDITOR, ADMIN}）或企业级（企业角色 OWNER/ADMIN）。VIEWER 只读不可上传。</p>
 */
@Slf4j
@Service
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final KnowledgeBaseMemberMapper knowledgeBaseMemberMapper;

    private final EnterpriseMembershipChecker membershipChecker;

    private final EnterpriseMemberMapper enterpriseMemberMapper;

    private final EnterpriseRoleMapper enterpriseRoleMapper;

    private final Clock clock;

    /** 本地磁盘存储根目录，由配置项 {@code knowflow.storage.local-dir} 注入。 */
    private final String localDir;

    /** 白名单扩展名集（小写），用于文件类型校验。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md");

    /** 企业级可管理知识库的角色编码集合（企业所有者与管理员可管理企业内全部知识库）。 */
    private static final Set<String> ENTERPRISE_MANAGER_ROLES = Set.of("OWNER", "ADMIN");

    /** 上传文件大小上限（10MB），用于业务层兜底校验（multipart 配置层已拦截超限请求）。 */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024L;

    /**
     * 解析失败原因（白名单固定短语，写入 failed_reason 列）。
     * 只存固定短语、不存异常 message / 堆栈——遵循日志白名单原则（见 AGENTS.md），
     * 失败详情由 WARN 日志承载，数据库只记录业务可读的归一化原因。
     */
    private static final String REASON_UNSUPPORTED_FORMAT = "解析失败：文件格式不支持";

    /** 解析失败原因（白名单固定短语）：文件损坏 / 读取失败 / 非法内容（IOException 或解析库异常）。 */
    private static final String REASON_CORRUPTED_FILE = "解析失败：文件已损坏";

    public DocumentServiceImpl(DocumentMapper documentMapper,
                               KnowledgeBaseMapper knowledgeBaseMapper,
                               KnowledgeBaseMemberMapper knowledgeBaseMemberMapper,
                               EnterpriseMembershipChecker membershipChecker,
                               EnterpriseMemberMapper enterpriseMemberMapper,
                               EnterpriseRoleMapper enterpriseRoleMapper,
                               Clock clock,
                               @Value("${knowflow.storage.local-dir}") String localDir) {
        this.documentMapper = documentMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseMemberMapper = knowledgeBaseMemberMapper;
        this.membershipChecker = membershipChecker;
        this.enterpriseMemberMapper = enterpriseMemberMapper;
        this.enterpriseRoleMapper = enterpriseRoleMapper;
        this.clock = clock;
        this.localDir = localDir;
    }

    @Override
    @Transactional
    public Document uploadDocument(Long userId, Long enterpriseId, Long knowledgeBaseId, MultipartFile file) {
        // 1. 资源权限校验：两级编辑者（资源级 EDITOR/ADMIN 或企业级 OWNER/ADMIN），
        //    同时确认知识库存在且 NORMAL。
        requireKnowledgeBaseEditor(userId, enterpriseId, knowledgeBaseId);

        // 2. 文件非空校验。
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.DOCUMENT_FILE_EMPTY);
        }

        // 3. 扩展名白名单校验。
        //    客户端文件名可能含路径（如 "../../a/b/c.txt"），必须取最后一段再截取扩展名，
        //    防路径穿越。同时转为小写比较，实现大小写不敏感的白名单匹配。
        String originalFilename = file.getOriginalFilename();
        // 使用 Paths.get() 取文件名部分：即使用户传入 "../../etc/passwd.exe"，也只取最后一段。
        String safeFileName = Paths.get(originalFilename).getFileName().toString();
        int dotIndex = safeFileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == safeFileName.length() - 1) {
            throw new BusinessException(ErrorCode.DOCUMENT_TYPE_NOT_ALLOWED);
        }
        String ext = safeFileName.substring(dotIndex + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCode.DOCUMENT_TYPE_NOT_ALLOWED);
        }

        // 4. 文件大小兜底校验（正常超限被 multipart 层拦截，此处仅作防御性校验）。
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE);
        }

        // 5. SHA-256 内容哈希（企业维度去重）。
        //    使用 java.security.MessageDigest，与 file_hash CHAR(64) 对齐。
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件读取失败");
        }
        String fileHash = sha256Hex(fileBytes);

        // 6. 去重：同一企业内已存在相同哈希的文档 → 409。
        //    企业维度去重：同一文件在企业内只允许一份，跨企业可各自上传。
        boolean alreadyExists = documentMapper.exists(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getEnterpriseId, enterpriseId)
                        .eq(Document::getFileHash, fileHash));
        if (alreadyExists) {
            throw new BusinessException(ErrorCode.DOCUMENT_ALREADY_EXISTS);
        }

        // 7. 写入磁盘（事务外语义——文件写入在 insert 前完成）。
        //    存储路径结构：localDir + "/" + enterpriseId + "/" + knowledgeBaseId + "/" + storageKey
        //    随机文件名（UUID + 扩展名）防猜测/覆盖。
        String storageKey = UUID.randomUUID() + "." + ext;
        Path targetDir = Paths.get(localDir, String.valueOf(enterpriseId), String.valueOf(knowledgeBaseId));
        Path targetFile = targetDir.resolve(storageKey);
        try {
            Files.createDirectories(targetDir);
            Files.write(targetFile, fileBytes);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件写入失败");
        }

        // 8. 插入数据库记录。
        Instant now = clock.instant();
        Document document = new Document();
        document.setEnterpriseId(enterpriseId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setUploaderUserId(userId);
        document.setFileName(safeFileName);
        document.setFileSize(file.getSize());
        document.setContentType(file.getContentType());
        document.setFileHash(fileHash);
        document.setStorageKey(storageKey);
        document.setStatus(DocumentStatus.UPLOADED);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        try {
            documentMapper.insert(document);
        } catch (Exception e) {
            // 插入失败时手动清理已写磁盘文件（磁盘操作无法随事务回滚，必须手动清理）。
            try {
                Files.deleteIfExists(targetFile);
            } catch (IOException cleanupEx) {
                log.warn("event=document_cleanup_failed storageKey={}", storageKey);
            }
            throw e;
        }

        // 只记录系统标识，不记录文件名与路径——文件名是用户提交的自由文本，按日志白名单规范排除。
        log.info("event=document_uploaded enterpriseId={} knowledgeBaseId={} documentId={} "
                        + "uploaderId={} fileSize={}",
                enterpriseId, knowledgeBaseId, document.getId(), userId, file.getSize());
        return document;
    }

    @Override
    @Transactional
    public Document parseDocument(Long userId, Long enterpriseId, Long knowledgeBaseId, Long documentId) {
        // 1. 权限与上传一致：资源级 EDITOR/ADMIN 或企业级 OWNER/ADMIN。
        requireKnowledgeBaseEditor(userId, enterpriseId, knowledgeBaseId);

        // 2. 用「id + enterpriseId + knowledgeBaseId」三条件定位文档，
        //    从数据库层面防跨企业、跨知识库的越权解析。
        Document document = documentMapper.selectOne(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getId, documentId)
                        .eq(Document::getEnterpriseId, enterpriseId)
                        .eq(Document::getKnowledgeBaseId, knowledgeBaseId));
        if (document == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        // 3. 状态机校验：只有 UPLOADED 可解析。已 READY（解析完成）、FAILED（终态）、
        //    PARSING（瞬时态，正常流程读不到）都拒绝——防止重复解析产生覆盖或二次消耗。
        if (document.getStatus() != DocumentStatus.UPLOADED) {
            throw new BusinessException(ErrorCode.DOCUMENT_STATUS_NOT_ALLOWED);
        }

        // 4. 磁盘文件存在性校验（先于 PARSING 落库）。
        //    文件缺失属于存储层异常，直接按 404 抛出——若先标记 PARSING 再抛错，
        //    文档会停留在无法恢复的瞬时态（PARSING 不在可解析范围内，将永远无法重试）。
        Path filePath = Paths.get(localDir, String.valueOf(enterpriseId),
                String.valueOf(knowledgeBaseId), document.getStorageKey());
        if (!Files.exists(filePath)) {
            throw new BusinessException(ErrorCode.DOCUMENT_FILE_MISSING);
        }

        // 5. 提前落库标记 PARSING：解析可能耗时，先更新状态再执行解析，
        //    使状态变化尽早可见、避免后续阶段重复解析（教学点：状态机先行落库）。
        //    当前学习阶段不做「条件更新 + 行锁」级别的并发防护，那是后续主题；
        //    本步已满足「同步解析 + 状态机」的教学目标。
        Instant now = clock.instant();
        document.setStatus(DocumentStatus.PARSING);
        document.setUpdatedAt(now);
        documentMapper.updateById(document);

        // 6. 按扩展名分发解析（扩展名取自 storageKey，与上传白名单一致）。
        //    其他扩展名理论上被上传白名单挡住，此处防御性处理为解析失败。
        String ext = extensionOf(document.getStorageKey());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            return markParsingFailed(document, documentId, REASON_UNSUPPORTED_FORMAT);
        }

        // 7. 提取纯文本。
        String content;
        try {
            content = extractText(filePath, ext);
        } catch (IOException | RuntimeException e) {
            // 解析失败 → FAILED 是文档的正常终态，不是系统错误，不抛出异常：
            // 调用方根据返回实体的 status 即可判断结果。异常本身不记录
            // message/堆栈（日志白名单原则），只记录白名单固定短语。
            return markParsingFailed(document, documentId, REASON_CORRUPTED_FILE);
        }

        // 8. 解析成功：写入提取文本，状态机流转到 READY。
        //    failed_reason 显式置 NULL（虽因状态机不变量——只有 UPLOADED 能到 READY，
        //    而 UPLOADED 的 failed_reason 恒为 NULL——实际恒为空，仍保持语义清晰）。
        document.setContent(content);
        document.setStatus(DocumentStatus.READY);
        document.setFailedReason(null);
        document.setUpdatedAt(clock.instant());
        documentMapper.updateById(document);

        // 只记录计数与标识，不记录文件名与内容本体。
        log.info("event=document_parsed enterpriseId={} knowledgeBaseId={} documentId={} contentLength={}",
                enterpriseId, knowledgeBaseId, documentId, content.length());
        return document;
    }

    /**
     * 按扩展名提取文档纯文本。
     *
     * <p>教学点：</p>
     * <ul>
     *   <li><strong>TXT/MD</strong>：纯文本格式，直接读字节转 UTF-8 字符串即可；</li>
     *   <li><strong>PDF</strong>：二进制格式，页面文本以对象/流形式内嵌，不能直接读字节当文本，
     *       必须用 PDFBox（{@link PDFTextStripper}）按 PDF 结构解析；</li>
     *   <li><strong>DOCX</strong>：本质是 zip 包的 OOXML 容器，正文分散在 XML 部件中，
     *       必须用 POI（{@link XWPFWordExtractor}）解包提取。</li>
     * </ul>
     *
     * @throws IOException 文件读取失败或解析库抛出的 IO 异常（由调用方归一化为 FAILED）
     */
    private static String extractText(Path filePath, String ext) throws IOException {
        return switch (ext) {
            case "txt", "md" -> new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            case "pdf" -> extractPdfText(filePath);
            case "docx" -> extractDocxText(filePath);
            // 白名单外的扩展名已在调用方拦截，这里仅作兜底。
            default -> throw new IllegalArgumentException("unsupported extension: " + ext);
        };
    }

    /**
     * PDF 文本提取（PDFBox 3.x）。
     *
     * <p>{@link Loader#loadPDF} 是 3.x 推荐入口；{@link PDDocument} 实现 {@code AutoCloseable}，
     * 用 try-with-resources 确保加载的底层资源随解析结束释放。</p>
     */
    private static String extractPdfText(Path filePath) throws IOException {
        try (PDDocument pdfDocument = Loader.loadPDF(filePath.toFile())) {
            return new PDFTextStripper().getText(pdfDocument);
        }
    }

    /**
     * DOCX 文本提取（Apache POI）。
     *
     * <p>{@link XWPFDocument} 与 {@link XWPFWordExtractor} 都实现 {@code AutoCloseable}，
     * 用 try-with-resources 确保 zip 包与内部流随解析结束释放。</p>
     */
    private static String extractDocxText(Path filePath) throws IOException {
        try (XWPFDocument docxDocument = new XWPFDocument(Files.newInputStream(filePath))) {
            try (XWPFWordExtractor extractor = new XWPFWordExtractor(docxDocument)) {
                return extractor.getText();
            }
        }
    }

    /**
     * 解析失败的统一收口：状态机流转到 FAILED + 写入白名单短语 + WARN 日志，不抛出异常。
     *
     * @return 更新后的文档实体（status = FAILED），调用方直接返回
     */
    private Document markParsingFailed(Document document, Long documentId, String reason) {
        document.setStatus(DocumentStatus.FAILED);
        document.setFailedReason(reason);
        document.setUpdatedAt(clock.instant());
        documentMapper.updateById(document);

        // WARN：可预期的异常输入（坏文件），记录固定短语即可，不输出异常堆栈。
        log.warn("event=document_parse_failed documentId={} reason={}", documentId, reason);
        return document;
    }

    /** 从 storageKey 提取小写扩展名（含点后部分）；无扩展名返回空串，由调用方按不支持处理。 */
    private static String extensionOf(String storageKey) {
        int dotIndex = storageKey.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == storageKey.length() - 1) {
            return "";
        }
        return storageKey.substring(dotIndex + 1).toLowerCase();
    }

    /**
     * 两级编辑者校验：当前用户必须是知识库的编辑者。
     *
     * <p>校验顺序（与 {@code requireKnowledgeBaseManager} 一致，但资源级放宽到
     * EDITOR 和 ADMIN 均可上传）：</p>
     * <ol>
     *   <li>企业存在（404）；正常成员身份（403）——先资源后权限；</li>
     *   <li>按「id + enterpriseId」组合定位知识库，不存在或 status != NORMAL → 404；</li>
     *   <li>查当前用户对该知识库的成员记录，memberRole ∈ {EDITOR, ADMIN} → 通过；</li>
     *   <li>否则查企业角色：code ∈ (OWNER, ADMIN) → 企业级管理，通过；</li>
     *   <li>都不满足 → 403。</li>
     * </ol>
     *
     * @return 校验通过后的知识库实体
     */
    private KnowledgeBase requireKnowledgeBaseEditor(Long userId, Long enterpriseId, Long knowledgeBaseId) {
        // 1. 先资源后权限：企业必须存在，操作者必须是该企业正常成员。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 2. 用「id + enterpriseId」组合定位知识库。禁用与不存在同样按 404 处理。
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, knowledgeBaseId)
                        .eq(KnowledgeBase::getEnterpriseId, enterpriseId));
        if (knowledgeBase == null || knowledgeBase.getStatus() != KnowledgeBaseStatus.NORMAL) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        // 3. 资源级：当前用户是知识库成员且 memberRole ∈ {EDITOR, ADMIN}。
        KnowledgeBaseMember member = knowledgeBaseMemberMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBaseMember>()
                        .eq(KnowledgeBaseMember::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeBaseMember::getUserId, userId));
        if (member != null) {
            KnowledgeBaseMemberRole role = member.getMemberRole();
            if (role == KnowledgeBaseMemberRole.EDITOR || role == KnowledgeBaseMemberRole.ADMIN) {
                return knowledgeBase;
            }
        }

        // 4. 企业级：企业 OWNER/ADMIN 可管理企业内所有知识库。
        EnterpriseMember enterpriseMember = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, userId));
        if (enterpriseMember != null && enterpriseMember.getRoleId() != null) {
            EnterpriseRole role = enterpriseRoleMapper.selectById(enterpriseMember.getRoleId());
            if (role != null && role.getStatus() == EnterpriseRoleStatus.NORMAL
                    && ENTERPRISE_MANAGER_ROLES.contains(role.getCode())) {
                return knowledgeBase;
            }
        }

        // 5. 都不满足 → 无上传权限。
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentVO> listDocuments(Long userId, Long enterpriseId, Long knowledgeBaseId) {
        // 1. 复用知识库可见性规则：企业存在 + 正常成员 + 知识库可见（成员 → 可见；
        //    PUBLIC 非成员 → 可见；PRIVATE 非成员 / 知识库不存在或已禁用 → 404）。
        requireVisibleKnowledgeBase(userId, enterpriseId, knowledgeBaseId);

        // 2. 按知识库查询文档列表，双重条件（enterpriseId + knowledgeBaseId）防跨企业访问，
        //    排序风格与知识库列表一致：created_at 倒序 + id 倒序。
        List<Document> documents = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getEnterpriseId, enterpriseId)
                        .eq(Document::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByDesc(Document::getCreatedAt)
                        .orderByDesc(Document::getId));

        // 3. 空列表用 List.of() 返回，避免 Service 层返回 null。
        if (documents.isEmpty()) {
            return List.of();
        }

        // 4. 转换为 VO，隐藏存储细节（fileHash / storageKey 不暴露）。
        List<DocumentVO> result = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            result.add(DocumentVO.from(doc));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Document getDocumentForDownload(Long userId, Long enterpriseId, Long knowledgeBaseId, Long documentId) {
        // 1. 下载 = 查看，可见即可下载：复用知识库可见性规则。
        requireVisibleKnowledgeBase(userId, enterpriseId, knowledgeBaseId);

        // 2. 用「id + enterpriseId + knowledgeBaseId」三条件定位文档，
        //    从数据库层面防跨企业、跨知识库的越权下载。
        Document document = documentMapper.selectOne(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getId, documentId)
                        .eq(Document::getEnterpriseId, enterpriseId)
                        .eq(Document::getKnowledgeBaseId, knowledgeBaseId));
        if (document == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        // 3. 校验磁盘文件存在：记录存在但文件缺失属于存储层异常，
        //    不暴露具体原因（404），避免泄露存储细节。
        Path filePath = Paths.get(localDir, String.valueOf(enterpriseId),
                String.valueOf(knowledgeBaseId), document.getStorageKey());
        if (!Files.exists(filePath)) {
            throw new BusinessException(ErrorCode.DOCUMENT_FILE_MISSING);
        }

        return document;
    }

    /**
     * 校验知识库对当前用户可见（下载 / 列表场景复用同一规则）。
     *
     * <p>规则完全复用 {@code KnowledgeBaseServiceImpl.getKnowledgeBase} 的可见性模型：</p>
     * <ol>
     *   <li>企业存在（{@code membershipChecker.requireEnterprise} → 404）；</li>
     *   <li>正常成员身份（{@code membershipChecker.requireActiveMember} → 403）；</li>
     *   <li>按「id + enterpriseId」组合定位知识库，不存在或 status != NORMAL →
     *       {@code KNOWLEDGE_BASE_NOT_FOUND}（不泄露存在性 / 状态）；</li>
     *   <li>查当前用户对该知识库的成员记录：存在 → 可见；
     *       不存在但 accessMode == PUBLIC → 可见；
     *       不存在且 PRIVATE → {@code KNOWLEDGE_BASE_NOT_FOUND}
     *       （隐私设计，与 getKnowledgeBase 保持一致）。</li>
     * </ol>
     */
    private void requireVisibleKnowledgeBase(Long userId, Long enterpriseId, Long knowledgeBaseId) {
        // 1. 先资源后权限：企业必须存在，操作者必须是该企业正常成员。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 2. 用「id + enterpriseId」组合定位知识库，禁用与不存在同样按 KNOWLEDGE_BASE_NOT_FOUND 处理，
        //    不泄露知识库是否存在及其状态。
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, knowledgeBaseId)
                        .eq(KnowledgeBase::getEnterpriseId, enterpriseId));
        if (knowledgeBase == null || knowledgeBase.getStatus() != KnowledgeBaseStatus.NORMAL) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        // 3. 查当前用户对该知识库的成员记录。
        KnowledgeBaseMember myMembership = knowledgeBaseMemberMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBaseMember>()
                        .eq(KnowledgeBaseMember::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeBaseMember::getUserId, userId));

        // 4. 可见性判定：成员 → 可见；非成员但 PUBLIC → 可见；PRIVATE 非成员 → KNOWLEDGE_BASE_NOT_FOUND。
        //    与 KnowledgeBaseServiceImpl.getKnowledgeBase 的隐私设计完全一致：
        //    PRIVATE 知识库对非成员不泄露存在性，返回 404 而非「无权访问」。
        if (myMembership != null) {
            return;
        }
        if (knowledgeBase.getAccessMode() == KnowledgeBaseAccessMode.PUBLIC) {
            return;
        }
        throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    }

    /** 计算字节数组的 SHA-256 十六进制小写字符串（64 位），与 {@code file_hash CHAR(64)} 对齐。 */
    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}