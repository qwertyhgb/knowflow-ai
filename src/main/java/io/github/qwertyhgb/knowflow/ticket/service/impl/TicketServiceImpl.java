package io.github.qwertyhgb.knowflow.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.qwertyhgb.knowflow.ai.service.SemanticSearchService;
import io.github.qwertyhgb.knowflow.ai.vo.RagCitationVO;
import io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketCreateRequest;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketReplyCreateRequest;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.ticket.entity.Ticket;
import io.github.qwertyhgb.knowflow.ticket.entity.TicketReply;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketReplyRole;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketStatus;
import io.github.qwertyhgb.knowflow.ticket.mapper.TicketMapper;
import io.github.qwertyhgb.knowflow.ticket.mapper.TicketReplyMapper;
import io.github.qwertyhgb.knowflow.ticket.service.TicketService;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketDetailVO;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketReplyVO;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 工单服务实现(Phase 13:创建 + AI 自动回答 + 我的列表/详情 + 分配/回复/状态流转)。
 *
 * <p><strong>AI 自动回答的完整链路</strong>(与 {@code RagChatServiceImpl} 同款 RAG 模式):</p>
 * <pre>{@code
 * 工单描述 → SemanticSearchService.search(当前企业知识库, topK=5)
 *         → 阈值过滤(score >= 0.3, score null 保留)
 *         → 有块:拼 Prompt(参考资料 + 客户问题)→ LLM 回答(带 [序号] 引用)→ 落 AI 回复
 *         → 无块:Human Handoff,落转人工提示(不调 LLM)
 * }</pre>
 *
 * <p><strong>工单状态机(Phase 13 第二步,单向流转):</strong></p>
 * <pre>{@code
 * OPEN(创建) → ASSIGNED(管理员分配) → PROCESSING(客服回复自动推进/手动开始)
 *            → RESOLVED(客服标记解决) → CLOSED(提交人确认或客服关闭,终态)
 * }</pre>
 * <p><strong>为什么状态机单向、拒绝跳变与回退?</strong>工单是多方协作的流程载体,
 * 每个状态代表协作的一环(待分配→已分配→处理中→已解决→已关闭)。跳变
 * (OPEN 直接 CLOSED)会跳过处理与确认环节,回退(RESOLVED 退回 PROCESSING)
 * 则需要「重开」语义支撑(谁有权重开?是否重新分配?)——教学阶段保持单向,
 * 把 Reopen 作为进阶主题单独设计,而不是随手放开回退。</p>
 *
 * <p><strong>为什么教学阶段用角色判断(currentRole)而非权限码?</strong>
 * 本步的权限判断是「身份三元组」:提交人(资源归属)、被分配客服(处理人)、
 * 企业 OWNER/ADMIN(管理兜底)——前两者 inherently 是数据归属判断(查工单字段),
 * 无法用静态权限码表达;只有「管理员分配」适合权限码化。为三类判断引入
 * ticket:assign/ticket:reply 等权限码会让简单场景复杂化,细粒度权限码化
 * (如自定义客服组)是后续优化主题。</p>
 *
 * <p><strong>为什么 AI 回答失败要降级而非报错?</strong>
 * AI 是工单系统的「增强能力」而不是「必要前提」:纯人工客服流程(创建→分配→处理)
 * 本身是完整闭环,AI 只是先尝试自动解决一部分。若因 AI 失败(未配 key、网络异常、
 * 限流)让客户连工单都提交不了,属于把「锦上添花」做成了「单点故障」——
 * 正确语义是降级为纯人工流程:落一条转人工提示,工单照常创建,等待客服处理。</p>
 *
 * <p><strong>为什么 Human Handoff 要落一条提示回复?</strong>
 * 落库的回复历史是工单的「对话流」,客服接手时靠它了解上下文。若 AI 答不出但
 * 什么都不落,客户视角是「提交后一片空白,不知道发生了什么」,客服视角是
 * 「不知道 AI 是否尝试过」。落一条明确的「已转人工」提示,双方都有确定预期;
 * 同时 AI 明确告知「答不出」而不是编造答案——这正是 Human Handoff 的定义。</p>
 *
 * <p><strong>为什么本步列表只看「我的工单」?</strong>
 * 客户(提交人)视角的列表只需要 enterprise_id + user_id 过滤,不涉及权限差异;
 * 而客服视角的「全部工单」需要 RBAC 权限(ticket:view 之类)与分配状态过滤,
 * 属于后续「分配」步骤的主题——一步只做一件事,保持每步可验证。</p>
 */
@Slf4j
@Service
public class TicketServiceImpl implements TicketService {

    /**
     * Human Handoff(转人工)提示文案:知识库无相关内容、AI 不可用或 AI 失败时,
     * 落为一条 role=AI 的回复。citations 为 null(没有任何知识库依据)。
     */
    private static final String HUMAN_HANDOFF_REPLY =
            "抱歉,知识库中暂未找到与您问题相关的解答,工单已转人工客服处理,请耐心等待。";

    /**
     * 系统提示词:与 {@code RagChatServiceImpl} 同款知识库助手提示词。
     *
     * <p>核心约束「只能依据参考资料回答 + [序号] 标注引用」直接复用——
     * 工单 AI 回答与 RAG 对话的引用机制完全一致:回答中的 [1][2] 与
     * citations 数组按序一一对应,前端可复用同一套引用卡片渲染。</p>
     */
    private static final String SYSTEM_PROMPT = """
            你是 KnowFlow 的企业知识库助手。
            回答时只能依据下方「参考资料」中的内容,不得编造或使用资料之外的知识;
            资料不足时直接说明「资料中没有相关内容」;
            回答中引用资料处用 [序号] 标注,序号对应参考资料编号;
            用简洁专业的中文回答。
            """;

    /** 检索条数:沿用 Phase 11 RAG 链路默认值 topK=5。 */
    private static final int RETRIEVAL_TOP_K = 5;

    /** 相关性阈值:沿用 Phase 11 RAG 链路默认值 0.3,低于此分数的块视为噪声丢弃。 */
    private static final double SCORE_THRESHOLD = 0.3;

    /**
     * 企业管理角色编码集合(OWNER/ADMIN):与 KnowledgeBaseServiceImpl 等
     * knowledge 模块先例一致的字面量集合——分配工单、客服兜底回复、标记解决等
     * 管理动作据此判断。教学阶段用角色编码判断而非权限码,见类注释。
     */
    private static final Set<String> ENTERPRISE_MANAGER_ROLES = Set.of("OWNER", "ADMIN");

    private final TicketMapper ticketMapper;

    private final TicketReplyMapper ticketReplyMapper;

    /** 语义检索服务(Phase 10 已完成):在当前企业知识库里找与工单描述相关的块。 */
    private final SemanticSearchService semanticSearchService;

    /** Spring AI 自动配置的 ChatClient 的可选提供者(可能不存在,取决于是否配置 api-key)。 */
    private final ObjectProvider<ChatClient> chatClientProvider;

    /** Jackson 3 的 JsonMapper:把 citations 序列化为 JSON 字符串落库。 */
    private final JsonMapper jsonMapper;

    /** UTC 时钟:统一时间来源,方便测试冻结时间。 */
    private final Clock clock;

    /**
     * 企业成员表 Mapper:分配工单时校验「目标客服是该企业正常成员」,
     * 与 EnterpriseContextFilter 的成员校验同一张表(enterprise_member)。
     */
    private final EnterpriseMemberMapper enterpriseMemberMapper;

    public TicketServiceImpl(TicketMapper ticketMapper,
                             TicketReplyMapper ticketReplyMapper,
                             SemanticSearchService semanticSearchService,
                             ObjectProvider<ChatClient> chatClientProvider,
                             JsonMapper jsonMapper,
                             Clock clock,
                             EnterpriseMemberMapper enterpriseMemberMapper) {
        this.ticketMapper = ticketMapper;
        this.ticketReplyMapper = ticketReplyMapper;
        this.semanticSearchService = semanticSearchService;
        this.chatClientProvider = chatClientProvider;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.enterpriseMemberMapper = enterpriseMemberMapper;
    }

    /**
     * AI 自动回答的结果:落库后的回复 + 是否基于知识库真正由 LLM 作答。
     *
     * <p>{@code knowledgeBased=false} 表示 Human Handoff(无相关块 / AI 不可用 / AI 失败),
     * 回复内容是固定的转人工提示;{@code true} 表示 LLM 依据检索块生成了回答。</p>
     */
    private record AiReply(TicketReply reply, boolean knowledgeBased) {
    }

    @Override
    @Transactional
    public TicketDetailVO createTicket(Long enterpriseId, Long userId, TicketCreateRequest request) {
        Instant now = clock.instant();

        // ---- 1. 落库工单 ----
        // 初始状态恒为 OPEN(待处理):无论 AI 是否答出,工单都需要人工确认/分配,
        // AI 自动回答不改变状态——它只是「先试一把」,不是「处理完成」。
        // assigneeId 保持 null:创建时尚未分配客服(分配是后续步骤)。
        Ticket ticket = new Ticket();
        ticket.setEnterpriseId(enterpriseId);
        ticket.setUserId(userId);
        ticket.setCategory(request.getCategory());
        ticket.setPriority(request.getPriority());
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setTitle(request.getTitle());
        ticket.setDescription(request.getDescription());
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticketMapper.insert(ticket);

        // ---- 2. 触发 AI 自动回答 ----
        // 三种结果都保证工单创建成功:有依据的 AI 回答 / 无依据的转人工提示 /
        // AI 失败的降级转人工提示(见 aiAutoReply 注释)。
        AiReply aiReply = aiAutoReply(ticket);

        // ---- 3. 组装返回:工单 + 已生成的 AI 回复 ----
        // 直接用内存中的回复对象组装,不再回查数据库:insert 后 MyBatis-Plus
        // 已回填自增 id,数据与库中一致,省一次查询。
        log.info("event=ticket_created ticketId={} enterpriseId={} userId={} aiReplied={}",
                ticket.getId(), enterpriseId, userId, aiReply.knowledgeBased());
        return TicketDetailVO.of(
                TicketVO.from(ticket),
                List.of(TicketReplyVO.from(aiReply.reply())));
    }

    /**
     * AI 自动回答:检索 → 过滤 → (有块)LLM 回答 / (无块、AI 不可用、AI 失败)转人工提示。
     *
     * <p>所有失败路径都落一条 role=AI、citations=null 的转人工提示回复,
     * 绝不向上抛异常——保证 {@link #createTicket} 的工单创建不被 AI 阻塞。</p>
     */
    private AiReply aiAutoReply(Ticket ticket) {
        // ---- AI 不可用(未配置 api-key):降级转人工 ----
        // ChatClient bean 未装配时 getIfAvailable 返回 null。这里与 RAG 对话不同:
        // RAG 对话没有 AI 就没有意义,抛 503 合理;工单的核心价值是「工单本身」,
        // AI 只是增强,所以降级而非报错(见类注释「为什么 AI 回答失败要降级」)。
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            log.warn("event=ticket_ai_reply handoff=true reason=chat_client_unavailable ticketId={}",
                    ticket.getId());
            return new AiReply(insertReply(ticket, HUMAN_HANDOFF_REPLY, null), false);
        }

        // ---- 检索:用工单描述作为检索问题 ----
        // 客户提交的 description 就是「问题的完整表述」,天然是最佳检索 query;
        // SemanticSearchService 内部已按企业上下文隔离(ES 向量索引带 enterprise 维度),
        // 检索结果就是「当前企业知识库」里的相关块。topK/阈值沿用 Phase 11 默认。
        List<SemanticSearchVO> candidates =
                semanticSearchService.search(ticket.getDescription(), RETRIEVAL_TOP_K);

        // ---- 阈值过滤(与 RagChatServiceImpl.retrieveAndFilter 同逻辑)----
        // score 为 null 的块保留(个别存储实现不返回分数,宁可保留不可误杀);
        // score 非 null 且低于阈值 → 噪声,丢弃,以免误导模型。
        List<SemanticSearchVO> filtered = candidates.stream()
                .filter(c -> c.getScore() == null || c.getScore() >= SCORE_THRESHOLD)
                .toList();

        // ---- 无相关块:Human Handoff,不调 LLM ----
        // 知识库里没有依据还硬让模型回答,模型只能编造——这正是幻觉的来源。
        // 明确告知「已转人工」比编造答案专业,也省下一次 LLM 调用(成本 + 延迟)。
        // 状态保持 OPEN:等待后续客服分配,本步不推进状态机。
        if (filtered.isEmpty()) {
            log.info("event=ticket_ai_reply handoff=true reason=no_relevant_chunks ticketId={} topK={} threshold={}",
                    ticket.getId(), RETRIEVAL_TOP_K, SCORE_THRESHOLD);
            return new AiReply(insertReply(ticket, HUMAN_HANDOFF_REPLY, null), false);
        }

        // ---- 拼装 Prompt:参考资料 + 客户问题(与 RagChatServiceImpl 同结构)----
        // 资料与客户输入分开拼、编号 [1][2] 与 citations 一一对应;
        // 系统提示词限定「只能依据资料作答」,从结构上弱化工单描述里的注入指令。
        String userMessage = "参考资料:\n%s\n\n客户问题:%s"
                .formatted(buildContext(filtered), ticket.getDescription());

        try {
            // ---- 调用 LLM(同步)----
            // 用 call().chatResponse() 拿 ChatResponse 再取文本(与 ConversationChatServiceImpl
            // 一致),后续若需要 token 统计可直接从 metadata.usage 扩展。
            var chatResponse = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage)
                    .call()
                    .chatResponse();
            String reply = chatResponse.getResult().getOutput().getText();

            // ---- 引用序列化:失败降级为空数组,不阻塞回答 ----
            // 引用是增强信息(给前端展示「查看原文」),回答本身更重要:
            // 序列化失败时存 "[]" 降级为无引用模式,不丢弃已生成的回答。
            String citationsJson;
            try {
                citationsJson = jsonMapper.writeValueAsString(toCitations(filtered));
            } catch (JacksonException e) {
                log.warn("event=ticket_citations_serialization_failed ticketId={} reason={}",
                        ticket.getId(), e.getClass().getSimpleName());
                citationsJson = "[]";
            }

            log.info("event=ticket_ai_reply aiReplied=true usedCount={} ticketId={}",
                    filtered.size(), ticket.getId());
            return new AiReply(insertReply(ticket, reply, citationsJson), true);
        } catch (RuntimeException e) {
            // ---- LLM 调用失败:降级转人工,不阻塞工单创建 ----
            // 网络/限流/超时等失败记录安全日志(不含工单描述原文与资料原文),
            // 落转人工提示回复——AI 是增强能力,失败不能让客户工单提交失败。
            log.warn("event=ticket_ai_reply handoff=true reason={} ticketId={}",
                    e.getClass().getSimpleName(), ticket.getId());
            return new AiReply(insertReply(ticket, HUMAN_HANDOFF_REPLY, null), false);
        }
    }

    /**
     * 落一条工单回复(role=AI,sender=null)并返回带回填 id 的实体。
     *
     * <p>senderId 恒为 null:AI 是系统服务,没有 sys_user 账号身份
     * (见 {@link TicketReply} 实体注释);citations 由调用方决定
     * (有知识库依据的回答传 JSON,转人工提示传 null)。</p>
     */
    private TicketReply insertReply(Ticket ticket, String content, String citations) {
        TicketReply reply = new TicketReply();
        reply.setTicketId(ticket.getId());
        reply.setRole(TicketReplyRole.AI);
        reply.setSenderId(null);
        reply.setContent(content);
        reply.setCitations(citations);
        reply.setCreatedAt(clock.instant());
        ticketReplyMapper.insert(reply);
        return reply;
    }

    /**
     * 把过滤后的块拼装为带编号的上下文(与 RagChatServiceImpl.buildContext 同逻辑)。
     *
     * <p>按顺序编号 [1][2][3]...,每块附带来源文件名与块序号;编号与回答中的
     * [序号] 一一对应(Citation 基础)。带索引的 for 循环天然递增,
     * 避免 list.indexOf() 的 O(n²) 查找。</p>
     */
    private String buildContext(List<SemanticSearchVO> blocks) {
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            SemanticSearchVO block = blocks.get(i);
            if (i > 0) {
                contextBuilder.append('\n');
            }
            contextBuilder.append("[%d] 来源:%s 第%d块\n%s"
                    .formatted(i + 1, block.getFileName(), block.getChunkIndex(), block.getChunkText()));
        }
        return contextBuilder.toString();
    }

    /**
     * 把过滤后的块映射为引用 VO 列表(与 RagChatServiceImpl.toCitations 同逻辑)。
     *
     * <p>字段与 {@link SemanticSearchVO} 一一对应直接透传,
     * 与回答中的 [序号] 顺序一致,序列化后存入 {@code ticket_reply.citations}。</p>
     */
    private List<RagCitationVO> toCitations(List<SemanticSearchVO> blocks) {
        return blocks.stream()
                .map(c -> RagCitationVO.of(c.getDocumentId(), c.getKnowledgeBaseId(),
                        c.getFileName(), c.getChunkIndex(), c.getChunkText(), c.getScore()))
                .toList();
    }

    @Override
    public List<TicketVO> listMyTickets(Long enterpriseId, Long userId) {
        // 「我的工单」:企业 + 提交人双维过滤,创建时间倒序(最新在前)。
        // 排序在 SQL 层完成(orderByDesc 生成 ORDER BY created_at DESC)。
        List<Ticket> tickets = ticketMapper.selectList(
                new LambdaQueryWrapper<Ticket>()
                        .eq(Ticket::getEnterpriseId, enterpriseId)
                        .eq(Ticket::getUserId, userId)
                        .orderByDesc(Ticket::getCreatedAt));
        return tickets.stream().map(TicketVO::from).toList();
    }

    @Override
    public TicketDetailVO getTicketDetail(Long enterpriseId, Long userId, String roleCode, Long ticketId) {
        Ticket ticket = requireTicket(enterpriseId, ticketId);

        // ---- 可见范围:提交人 或 客服(被分配处理人 / 企业 OWNER/ADMIN)----
        // Phase 13 第二步起工单进入协作处理:被分配的客服必须能看详情才能接手处理
        // (只靠回复接口的返回看不全上下文);OWNER/ADMIN 是管理兜底,即使没被
        // 分配也可介入。其他成员(既非提交人也非客服)统一 404,与「不泄露
        // 存在性」的既有语义一致——知道 ticketId 不等于有权访问它。
        boolean allowed = ticket.getUserId().equals(userId) || isSupportMember(ticket, userId, roleCode);
        if (!allowed) {
            throw new BusinessException(ErrorCode.TICKET_NOT_FOUND);
        }

        TicketDetailVO detail = buildTicketDetail(ticket);
        log.info("event=ticket_detail_viewed ticketId={} enterpriseId={} userId={} replyCount={}",
                ticketId, enterpriseId, userId, detail.getReplies().size());
        return detail;
    }

    // ==================== 分配(Phase 13 第二步)====================

    @Override
    public TicketVO assignTicket(Long enterpriseId, Long operatorUserId, String operatorRoleCode,
                                 Long ticketId, Long assigneeId) {
        // ---- 操作者权限:仅企业 OWNER/ADMIN ----
        // 分配是管理动作(决定「谁来做」),与成员管理的 ADMIN 权限对齐。
        // 教学阶段用角色编码判断,权限码化(ticket:assign)是后续优化主题(见类注释)。
        if (!ENTERPRISE_MANAGER_ROLES.contains(operatorRoleCode)) {
            log.warn("event=ticket_assign_rejected reason=not_manager ticketId={} operatorId={}",
                    ticketId, operatorUserId);
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        Ticket ticket = requireTicket(enterpriseId, ticketId);

        // ---- 状态机校验:只有 OPEN 可分配 ----
        // 已分配(ASSIGNED)的工单重复分配等于换人,涉及旧客服的工作交接语义;
        // 处理中及之后更不允许。教学阶段限定「一次分配、不可改派」,
        // 改派(Reassign)与重开一样是进阶主题。
        if (ticket.getStatus() != TicketStatus.OPEN) {
            throw new BusinessException(ErrorCode.TICKET_INVALID_TRANSITION);
        }

        // ---- assignee 校验:目标用户必须是该企业正常成员 ----
        // 与 EnterpriseContextFilter 的成员校验同表同条件:被分配人必须是
        // NORMAL 状态成员(已禁用/已退出的成员不能接单)。
        // 非成员 → 400:这是调用方提交了非法参数(目标 ID 不满足业务前提),
        // 而非资源冲突。
        EnterpriseMember assigneeMember = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, assigneeId)
                        .eq(EnterpriseMember::getStatus, EnterpriseMemberStatus.NORMAL));
        if (assigneeMember == null) {
            log.warn("event=ticket_assign_rejected reason=assignee_not_member ticketId={} assigneeId={}",
                    ticketId, assigneeId);
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "目标客服不是该企业的正常成员");
        }

        // ---- 执行分配:OPEN → ASSIGNED,同时落处理人 ----
        // assignee_id 与 status 必须同一次更新原子写入(单行 UPDATE 天然原子),
        // 保证不会出现「ASSIGNED 但没有处理人」的中间态。
        ticket.setAssigneeId(assigneeId);
        ticket.setStatus(TicketStatus.ASSIGNED);
        ticket.setUpdatedAt(clock.instant());
        ticketMapper.updateById(ticket);

        // 日志白名单:只记录标识,不含工单内容
        log.info("event=ticket_assigned ticketId={} assigneeId={} operatorId={}",
                ticketId, assigneeId, operatorUserId);
        return TicketVO.from(ticket);
    }

    // ==================== 回复(Phase 13 第二步)====================

    @Override
    public TicketDetailVO addReply(Long enterpriseId, Long userId, String roleCode,
                                   Long ticketId, TicketReplyCreateRequest request) {
        Ticket ticket = requireTicket(enterpriseId, ticketId);

        // ---- 身份判定:提交人 / 被分配客服 / 企业管理员 ----
        boolean isSubmitter = ticket.getUserId().equals(userId);
        boolean isAssignee = ticket.getAssigneeId() != null && ticket.getAssigneeId().equals(userId);
        // 客服定义 = 被分配处理人 或 企业 OWNER/ADMIN。
        // 【为什么 OWNER/ADMIN 也算客服?】管理兜底:小团队里管理员往往直接处理
        // 工单(没有专职客服);即使有专职客服,管理员也需要能在客服休假/离职时
        // 介入。把「谁能处理」收敛为一个判定函数,后续加「客服组」时只改这里。
        boolean isSupport = isAssignee || ENTERPRISE_MANAGER_ROLES.contains(roleCode);

        // ---- 权限:提交人或客服可回复,其他成员拒绝 ----
        if (!isSubmitter && !isSupport) {
            log.warn("event=ticket_reply_rejected reason=not_participant ticketId={} userId={}",
                    ticketId, userId);
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        // ---- 回复角色判定(服务端决定,客户端不可指定)----
        // 提交人且非被分配客服 → USER(客户视角的补充说明);
        // 被分配客服、或非提交人的 OWNER/ADMIN → SUPPORT(处理方视角)。
        // 【为什么提交人优先判 USER?】管理员自己也提工单时,他在自己的工单里
        // 发言语义是「客户补充」,不是「客服处理」——除非他被分配为该单的处理人。
        TicketReplyRole replyRole = (isSubmitter && !isAssignee) ? TicketReplyRole.USER : TicketReplyRole.SUPPORT;

        // ---- 落库回复(sender 记录「谁说的」,USER/SUPPORT 均有用户身份)----
        TicketReply reply = new TicketReply();
        reply.setTicketId(ticketId);
        reply.setRole(replyRole);
        reply.setSenderId(userId);
        reply.setContent(request.getContent());
        reply.setCreatedAt(clock.instant());
        ticketReplyMapper.insert(reply);

        // ---- SUPPORT 回复的自动推进:OPEN/ASSIGNED → PROCESSING ----
        // 【为什么自动推进?】客服回复本身就是「开始处理」的行为证据——
        // 再要求手动点一次「开始处理」是多余的操作负担,容易漏点导致状态与
        // 实际进展脱节。这是工单/流程系统的常见设计:动作即流转。
        // USER 回复不改状态:客户的补充说明不改变处理进度。
        // RESOLVED/CLOSED 状态下的回复也不改状态(重开 Reopen 是进阶主题,本步不做)。
        if (replyRole == TicketReplyRole.SUPPORT
                && (ticket.getStatus() == TicketStatus.OPEN || ticket.getStatus() == TicketStatus.ASSIGNED)) {
            ticket.setStatus(TicketStatus.PROCESSING);
            if (ticket.getAssigneeId() == null) {
                // OPEN 工单被管理员兜底直接处理时还没走过分配动作:
                // 把首个介入处理的客服记为处理人,维持「非 OPEN 状态必有处理人」
                // 的数据不变量(与 V14 表注释呼应),后续解决/关闭才有明确责任人。
                ticket.setAssigneeId(userId);
            }
            ticket.setUpdatedAt(clock.instant());
            ticketMapper.updateById(ticket);
        }

        log.info("event=ticket_reply_added ticketId={} userId={} role={}",
                ticketId, userId, replyRole.name());
        return buildTicketDetail(ticket);
    }

    // ==================== 状态流转(Phase 13 第二步)====================

    @Override
    public TicketVO updateTicketStatus(Long enterpriseId, Long userId, String roleCode,
                                       Long ticketId, TicketStatusUpdateRequest request) {
        Ticket ticket = requireTicket(enterpriseId, ticketId);
        TicketStatus target = request.getStatus();

        // ---- OPEN/ASSIGNED 不允许直接设置 ----
        // 这两个状态由创建/分配动作驱动,直接设置会绕过动作的副作用
        // (如分配要同时落 assignee_id),见 TicketStatusUpdateRequest 注释。
        if (target == TicketStatus.OPEN || target == TicketStatus.ASSIGNED) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "OPEN/ASSIGNED 状态由分配/回复动作驱动,不能直接设置");
        }

        boolean isSubmitter = ticket.getUserId().equals(userId);
        boolean isSupport = isSupportMember(ticket, userId, roleCode);

        // ---- 状态机合法性表 + 操作者权限 ----
        // 显式列出每个目标状态的「合法来源状态 + 允许的操作者」,不满足即拒绝:
        //   * 来源状态不合法 → 409 TICKET_INVALID_TRANSITION(跳变/回退);
        //   * 来源合法但操作者无权 → 403(如提交人不能标解决——是否解决由处理方说了算)。
        // 先校验状态再校验权限:让「非法跳变」优先暴露为 409,语义更聚焦。
        switch (target) {
            case PROCESSING -> {
                // 开始处理:仅 ASSIGNED → PROCESSING,操作者必须是客服。
                // (ASSIGNED → PROCESSING 的另一条路径是客服回复自动推进,这里
                // 覆盖「已分配但尚未回复就开工」的手动场景。)
                if (ticket.getStatus() != TicketStatus.ASSIGNED) {
                    throw new BusinessException(ErrorCode.TICKET_INVALID_TRANSITION);
                }
                if (!isSupport) {
                    throw new BusinessException(ErrorCode.FORBIDDEN);
                }
                // 防御:ASSIGNED 却无处理人属于数据漂移(正常流程分配必落 assignee)
                if (ticket.getAssigneeId() == null) {
                    throw new BusinessException(ErrorCode.TICKET_NOT_ASSIGNED);
                }
            }
            case RESOLVED -> {
                // 标记解决:仅 PROCESSING → RESOLVED,操作者必须是客服。
                // 解决由处理方标记——只有真正处理的人才有资格宣告「处理完了」;
                // 提交人若认为没解决,应通过回复继续沟通(而非自行标记)。
                if (ticket.getStatus() != TicketStatus.PROCESSING) {
                    throw new BusinessException(ErrorCode.TICKET_INVALID_TRANSITION);
                }
                if (!isSupport) {
                    throw new BusinessException(ErrorCode.FORBIDDEN);
                }
                if (ticket.getAssigneeId() == null) {
                    throw new BusinessException(ErrorCode.TICKET_NOT_ASSIGNED);
                }
            }
            case CLOSED -> {
                // 关闭(终态):两条合法路径——
                //   * PROCESSING → CLOSED(客服):无需用户确认的场景(如无效工单、
                //     重复工单,客服直接关掉省去等待);
                //   * RESOLVED → CLOSED(提交人确认 或 客服):提交人确认关闭体现
                //     「用户认可已解决」——解决是客服的判断,关闭是用户的认可,
                //     两个动作分开才让「解决了但用户不买账」有表达的出口
                //     (用户不确认,工单停在 RESOLVED)。
                if (ticket.getStatus() == TicketStatus.PROCESSING) {
                    if (!isSupport) {
                        throw new BusinessException(ErrorCode.FORBIDDEN);
                    }
                } else if (ticket.getStatus() == TicketStatus.RESOLVED) {
                    if (!isSubmitter && !isSupport) {
                        throw new BusinessException(ErrorCode.FORBIDDEN);
                    }
                } else {
                    // OPEN/ASSIGNED 未处理完不能直接关闭 → 409
                    throw new BusinessException(ErrorCode.TICKET_INVALID_TRANSITION);
                }
            }
            default -> throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "OPEN/ASSIGNED 状态由分配/回复动作驱动,不能直接设置");
        }

        TicketStatus fromStatus = ticket.getStatus();
        ticket.setStatus(target);
        ticket.setUpdatedAt(clock.instant());
        ticketMapper.updateById(ticket);

        log.info("event=ticket_status_changed ticketId={} fromStatus={} toStatus={} operatorId={}",
                ticketId, fromStatus.name(), target.name(), userId);
        return TicketVO.from(ticket);
    }

    // ==================== 共享私有方法 ====================

    /**
     * 加载并校验工单:存在且属于当前企业,否则统一抛 {@code TICKET_NOT_FOUND}(404)。
     *
     * <p>「不存在」与「跨企业」统一 404 不泄露存在性;提交人级别的校验由
     * 各调用方按自己的语义补充(详情看提交人或客服,分配只看管理员身份)。</p>
     */
    private Ticket requireTicket(Long enterpriseId, Long ticketId) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || !ticket.getEnterpriseId().equals(enterpriseId)) {
            throw new BusinessException(ErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }

    /**
     * 判断用户是否是该工单的「客服」(处理方):被分配处理人 或 企业 OWNER/ADMIN。
     *
     * <p>管理兜底逻辑集中在这一个函数,后续引入「客服组」概念时只需扩展此处。</p>
     */
    private boolean isSupportMember(Ticket ticket, Long userId, String roleCode) {
        return (ticket.getAssigneeId() != null && ticket.getAssigneeId().equals(userId))
                || ENTERPRISE_MANAGER_ROLES.contains(roleCode);
    }

    /**
     * 组装工单详情:工单 + 全部回复(按创建时间升序,对话流从头到尾回放)。
     *
     * <p>详情组装逻辑收敛为一处:工单详情接口、回复接口共用,
     * 保证两条路径返回的结构与排序永远一致。</p>
     */
    private TicketDetailVO buildTicketDetail(Ticket ticket) {
        List<TicketReply> replies = ticketReplyMapper.selectList(
                new LambdaQueryWrapper<TicketReply>()
                        .eq(TicketReply::getTicketId, ticket.getId())
                        .orderByAsc(TicketReply::getCreatedAt));
        return TicketDetailVO.of(
                TicketVO.from(ticket),
                replies.stream().map(TicketReplyVO::from).toList());
    }
}
