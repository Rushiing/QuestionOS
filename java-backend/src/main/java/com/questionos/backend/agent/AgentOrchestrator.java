package com.questionos.backend.agent;

import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.MessageRole;
import com.questionos.backend.domain.SessionMode;
import com.questionos.backend.integrations.OpenClawInvokeService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 思维校准：单轮主校准 agent。
 * 沙盘：用户每发一条消息，仅一位 agent 回复（轮转），内置角色 system prompt 与 v0.2 Python registry 对齐。
 */
@Service
public class AgentOrchestrator {
    /** 首轮用户议题写入 prompt 的上限，避免撑爆上下文 */
    private static final int SANDBOX_CORE_TOPIC_MAX_CHARS = 1200;

    private final MainCalibrateAgent mainAgent;
    private final OpenClawInvokeService invokeService;
    private final AdaptiveDepthGate adaptiveDepthGate;

    public AgentOrchestrator(
            MainCalibrateAgent mainAgent,
            OpenClawInvokeService invokeService,
            AdaptiveDepthGate adaptiveDepthGate
    ) {
        this.mainAgent = mainAgent;
        this.invokeService = invokeService;
        this.adaptiveDepthGate = adaptiveDepthGate;
    }

    public Flux<AgentReplyChunk> runPipeline(
            String sessionId,
            long turnId,
            String input,
            SessionMode mode,
            List<ConversationMessage> history,
            int sandboxRoundIndex,
            String sandboxDeliberationScene
    ) {
        if (mode == SessionMode.CALIBRATION) {
            return Flux.just(new AgentReplyChunk("agent_start", "main-calibrate|主校准 Agent"))
                    .concatWith(mainAgent.replyWithHistory(sessionId, turnId, input, history))
                    .concatWithValues(new AgentReplyChunk("done", "本轮结束"));
        }
        return sandboxSingleReply(history, sandboxRoundIndex, sandboxDeliberationScene)
                .concatWithValues(new AgentReplyChunk("done", "本轮结束"));
    }

    private Flux<AgentReplyChunk> sandboxSingleReply(
            List<ConversationMessage> history,
            int sandboxRoundIndex,
            String sandboxDeliberationSceneRaw
    ) {
        SandboxDeliberationScene scene = SandboxDeliberationScene.parseStored(sandboxDeliberationSceneRaw);
        List<SandboxAgoraTurnPlan.BuiltinTurn> plan = SandboxAgoraTurnPlan.fourBuiltin(scene);

        int cycleLen = plan.size();
        SandboxAgoraTurnPlan.BuiltinTurn b = plan.get(Math.floorMod(sandboxRoundIndex, cycleLen));
        String latestUser = latestUserMessage(history);
        List<ConversationMessage> prior = priorHistory(history);

        String phaseBlock = deliberationPhaseBlock(b.slot(), sandboxRoundIndex, cycleLen, prior);
        String speakerId = speakerIdForSlot(b.slot());
        String sys = augmentBuiltinSystemPrompt(b);
        String done = b.displayName() + " 发言结束。";
        if (b.slot() == SandboxSlot.INTEGRATOR) {
            // 整合官位双模式：终局报告 vs 中场小结。
            // 收束时机三选一满足即终局：共识门 HIGH / 已进入第二圈（lap>=1）/ 用户明确要求收束；
            // 否则只出中场小结，把审议引向第二圈交叉审查（修复"第一圈刚完就甩终局报告"的仓促感）。
            List<String> lapAnalyses = lapAnalysesForGate(prior);
            int lap = sandboxRoundIndex >= 0 && cycleLen > 0 ? sandboxRoundIndex / cycleLen : 0;
            boolean userWantsClose = userRequestedCloseout(latestUser);
            if (lapAnalyses.isEmpty()) {
                String userMsg = buildIntegratorUserMessage(prior, latestUser, scene, phaseBlock, null);
                return oneSpeakerWithDefaultLlm(speakerId, b.displayName(), sys, userMsg, done);
            }
            return adaptiveDepthGate.assessAsync(lapAnalyses)
                    .flatMapMany(assessment -> {
                        boolean closeOut = userWantsClose || lap >= 1 || assessment.isHighConsensus();
                        if (closeOut) {
                            String userMsg = buildIntegratorUserMessage(prior, latestUser, scene, phaseBlock, assessment);
                            return oneSpeakerWithDefaultLlm(speakerId, b.displayName(), sys, userMsg, done);
                        }
                        String interimSys = b.personaPrefix().trim() + "\n\n" + SandboxBuiltInPrompts.INTERIM_SYNTHESIS;
                        String interimMsg = buildInterimUserMessage(prior, latestUser, scene, assessment);
                        return oneSpeakerWithDefaultLlm(speakerId, b.displayName(), interimSys, interimMsg, done);
                    });
        }
        return oneSpeakerWithDefaultLlm(
                speakerId,
                b.displayName(),
                sys,
                buildAttackerUserMessage(prior, latestUser, scene, phaseBlock),
                done
        );
    }

    private static String speakerIdForSlot(SandboxSlot slot) {
        return switch (slot) {
            case AUDITOR -> "auditor";
            case RISK_OFFICER -> "risk_officer";
            case VALUE_JUDGE -> "value_judge";
            case INTEGRATOR -> "integrator";
            default -> "auditor";
        };
    }

    private static String augmentBuiltinSystemPrompt(SandboxAgoraTurnPlan.BuiltinTurn b) {
        String base = switch (b.slot()) {
            case AUDITOR -> SandboxBuiltInPrompts.AUDITOR;
            case RISK_OFFICER -> SandboxBuiltInPrompts.RISK_OFFICER;
            case VALUE_JUDGE -> SandboxBuiltInPrompts.VALUE_JUDGE;
            case INTEGRATOR -> SandboxBuiltInPrompts.INTEGRATOR;
            default -> SandboxBuiltInPrompts.AUDITOR;
        };
        return b.personaPrefix().trim() + "\n\n" + base;
    }

    /**
     * 与 Agora 式协议对齐的轻量阶段：会话中**尚无任何 Agent 发言**时为独立分析；否则（含用户第二轮起）攻防位进入交叉审查。
     * 整合同一轮多圈时仍可用 lap≥1 强化交锋。
     */
    private static String deliberationPhaseBlock(
            SandboxSlot slot,
            int sandboxRoundIndex,
            int cycleLen,
            List<ConversationMessage> prior
    ) {
        if (cycleLen <= 0) {
            cycleLen = 4;
        }
        if (slot == SandboxSlot.INTEGRATOR) {
            return """
                    ## 本轮审议阶段：综合裁决（首席整合官）

                    先从下文「前序观点速览」中挑出**张力最大的一对主张**，用各半句点明这组「正题 / 反题」（点名出处），再按既定 Markdown 输出；博弈复盘三条须能看出与该张力对应。
                    """;
        }
        int lap = sandboxRoundIndex / cycleLen;
        boolean cross = priorHasAgentReply(prior) || lap >= 1;
        if (!cross) {
            return """
                    ## 本轮审议阶段：独立分析

                    - 立足用户核心议题与已知信息独立推演；前序发言仅作背景，不要点名与其他角色交锋。
                    - 若提及他人观点，用「有一种观点是」等匿名化表述。
                    """;
        }
        return """
                ## 本轮审议阶段：交叉审查（辩证）

                - 从下文「前序观点速览」表中**选定至少一位**参与者（用其展示名点名），针对其**一条具体论断**表明赞成或反对，并给出你的理由或反例。
                - 选靶标准：选与你角色立场**张力最大**的那条主张，而不是最容易附和的。
                - 用 1 句话给出合题取向：在什么前提下双方可部分同时成立。
                - 为完成论证，总字数允许在约 320 字内，仍以可验证的追问或行动收口。
                """;
    }

    private static boolean priorHasAgentReply(List<ConversationMessage> prior) {
        if (prior == null || prior.isEmpty()) {
            return false;
        }
        for (ConversationMessage m : prior) {
            if (m.role() == MessageRole.AGENT && m.content() != null && !m.content().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private Flux<AgentReplyChunk> oneSpeakerWithDefaultLlm(
            String speakerId,
            String displayName,
            String systemPrompt,
            String userMessage,
            String doneLine
    ) {
        return Flux.just(new AgentReplyChunk("agent_start", speakerId + "|" + displayName))
                .concatWith(
                        invokeService.invokeDefaultLlm(systemPrompt, userMessage)
                                .flatMapMany(text -> Flux.just(new AgentReplyChunk("agent_chunk", text)))
                                .onErrorResume(e -> Flux.just(
                                        new AgentReplyChunk("agent_error", "调用失败: " + e.getMessage()),
                                        new AgentReplyChunk("agent_chunk", fallbackLine(speakerId))
                                ))
                )
                .concatWithValues(new AgentReplyChunk("agent_done", doneLine))
                .delayElements(Duration.ofMillis(80));
    }

    private static String fallbackLine(String speakerId) {
        return switch (speakerId) {
            case "auditor" -> "（降级）先列出与你决策直接相关的三项成本与收益，再选一个数字指标作为本周验证目标。";
            case "risk_officer" -> "（降级）最坏情况是什么？如果它发生，你最先失去的会是什么？";
            case "value_judge" -> "（降级）这件事若做成，你希望一年后仍认同自己的哪个选择？";
            case "integrator" -> "（降级）用一句话写下你的决策假设，并写下一个可在一周内验证的事实。";
            default -> "（降级）请简要重述你的目标，并补充一个可验证的下一步。";
        };
    }

    private static String latestUserMessage(List<ConversationMessage> history) {
        if (history.isEmpty()) return "";
        ConversationMessage last = history.get(history.size() - 1);
        return last.content() == null ? "" : last.content().trim();
    }

    private static List<ConversationMessage> priorHistory(List<ConversationMessage> history) {
        if (history.isEmpty()) return List.of();
        if (history.get(history.size() - 1).role() == MessageRole.USER) {
            return history.subList(0, history.size() - 1);
        }
        return history;
    }

    private String displayNameForSpeakerId(String id, SandboxDeliberationScene scene) {
        if (id == null) return "助手";
        return switch (id) {
            case "auditor", "risk_officer", "value_judge", "integrator" ->
                    SandboxAgoraTurnPlan.displayNameForSpeaker(scene, id);
            case "sandbox-route" -> "审议路由";
            case "sandbox-classify" -> "议题分诊";
            default -> id;
        };
    }

    /** 单条前序发言注入上下文的长度上限：防止多轮沙盘 prompt 无限膨胀 */
    private static final int PRIOR_AGENT_MESSAGE_MAX_CHARS = 1500;

    private static boolean isDeliberationAgentMessage(ConversationMessage m) {
        return m.role() == MessageRole.AGENT
                && m.content() != null && !m.content().isBlank()
                && !"sandbox-route".equals(m.agentSpeakerId())
                && !"sandbox-classify".equals(m.agentSpeakerId());
    }

    private String formatPriorAgents(List<ConversationMessage> prior, SandboxDeliberationScene scene) {
        StringBuilder sb = new StringBuilder();
        for (ConversationMessage m : prior) {
            if (!isDeliberationAgentMessage(m)) {
                continue;
            }
            String label = displayNameForSpeakerId(m.agentSpeakerId(), scene);
            String content = m.content().trim();
            if (content.length() > PRIOR_AGENT_MESSAGE_MAX_CHARS) {
                content = content.substring(0, PRIOR_AGENT_MESSAGE_MAX_CHARS) + "\n…（该发言过长已截断，核心主张见上方速览表）";
            }
            sb.append("【").append(label).append("】：").append(content).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 提取一条发言的「一句话核心主张」：跳过 Markdown 标题/表格/引用行，取首个实质段落的首句。
     * 首轮发言已被要求以一句话总结开头，因此确定性提取即可，不需要额外 LLM 调用。
     */
    private static String extractCoreClaim(String content) {
        if (content == null) {
            return "";
        }
        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("---")
                    || line.startsWith("|") || line.startsWith(">")) {
                continue;
            }
            String cleaned = line.replaceAll("[*_`]", "")
                    .replaceFirst("^[-•]\\s*", "")
                    .replaceFirst("^\\d+[.、)]\\s*", "")
                    .trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            int end = -1;
            for (int i = 0; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);
                if (c == '。' || c == '！' || c == '？'
                        || ((c == '.' || c == '!' || c == '?') && i >= 12)) {
                    end = i + 1;
                    break;
                }
            }
            String sentence = end > 0 ? cleaned.substring(0, end) : cleaned;
            return sentence.length() > 90 ? sentence.substring(0, 90) + "…" : sentence;
        }
        return "";
    }

    /** 前序观点速览表：交叉审查轮从中选定交锋对象与具体论断，避免面对全文文字墙时泛泛回应 */
    private String formatPriorClaimsTable(List<ConversationMessage> prior, SandboxDeliberationScene scene) {
        StringBuilder rows = new StringBuilder();
        for (ConversationMessage m : prior) {
            if (!isDeliberationAgentMessage(m)) {
                continue;
            }
            String claim = extractCoreClaim(m.content());
            if (claim.isEmpty()) {
                continue;
            }
            rows.append("| ").append(displayNameForSpeakerId(m.agentSpeakerId(), scene))
                    .append(" | ").append(claim.replace("|", "／")).append(" |\n");
        }
        if (rows.isEmpty()) {
            return "";
        }
        return "| 发言人 | 一句话核心主张 |\n|---|---|\n" + rows;
    }

    /** 进入事实清单的用户补充上限：条数与单条长度（防 prompt 膨胀，保留最近的回答） */
    private static final int USER_FACTS_MAX_ITEMS = 12;
    private static final int USER_FACTS_ITEM_MAX_CHARS = 200;

    /**
     * 用户在审议过程中的全部补充回答（首条=核心议题、末条=最新发言，两者单列，此处取中间部分）。
     * 没有这份清单时，发言人只能看见用户的第一句和最后一句——中间对追问的回答全部不可见，
     * 输出必然退化为与用户处境无关的通用分析（2026-06-12 用户实测体感的根因）。
     */
    private String formatUserFacts(List<ConversationMessage> prior, SandboxDeliberationScene scene) {
        // 问答配对：每条用户回答标注它回应的是谁的哪条追问。没有配对时，
        // 「我会选择私下调整」这类回答脱离其假设性前提就成了歧义句，
        // 后续发言人会把"对假设情境的选择"误读成"改变当下行动"（2026-06-12 实测）。
        List<String> facts = new ArrayList<>();
        boolean skippedFirst = false;
        String pendingAsker = null;
        String pendingQuestion = null;
        for (ConversationMessage m : prior) {
            if (isDeliberationAgentMessage(m)) {
                String q = extractQuestionLine(m.content());
                if (q != null) {
                    pendingAsker = displayNameForSpeakerId(m.agentSpeakerId(), scene);
                    pendingQuestion = q;
                }
                continue;
            }
            if (m.role() != MessageRole.USER || m.content() == null || m.content().isBlank()) {
                continue;
            }
            if (!skippedFirst) {
                skippedFirst = true; // 首条已作为「核心议题」单列
                continue;
            }
            String t = m.content().trim();
            if (t.length() > USER_FACTS_ITEM_MAX_CHARS) {
                t = t.substring(0, USER_FACTS_ITEM_MAX_CHARS) + "…";
            }
            if (pendingQuestion != null) {
                facts.add("（回应 " + pendingAsker + " 的追问「" + pendingQuestion + "」）" + t);
            } else {
                facts.add(t);
            }
            pendingAsker = null;
            pendingQuestion = null;
        }
        if (facts.isEmpty()) {
            return "";
        }
        if (facts.size() > USER_FACTS_MAX_ITEMS) {
            facts = facts.subList(facts.size() - USER_FACTS_MAX_ITEMS, facts.size());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < facts.size(); i++) {
            sb.append(i + 1).append(". ").append(facts.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    /** 取一条发言中以 ❓ 开头的追问行（截断到 80 字），没有则返回 null */
    private static String extractQuestionLine(String content) {
        if (content == null) {
            return null;
        }
        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("❓")) {
                String q = line.substring(1).trim().replaceAll("[*_`]", "");
                if (q.isEmpty()) {
                    continue;
                }
                return q.length() > 80 ? q.substring(0, 80) + "…" : q;
            }
        }
        return null;
    }

    /** 最新发言的问答绑定：用户最新一句通常是在回答上一位发言人的追问，把那条追问找出来 */
    private String latestAnswerBinding(List<ConversationMessage> prior, SandboxDeliberationScene scene) {
        for (int i = prior.size() - 1; i >= 0; i--) {
            ConversationMessage m = prior.get(i);
            if (m.role() == MessageRole.USER) {
                return null; // 最近一条已是用户消息（不应发生），不做绑定
            }
            if (isDeliberationAgentMessage(m)) {
                String q = extractQuestionLine(m.content());
                if (q == null) {
                    return null;
                }
                return "> 注意：这句话是在回答 " + displayNameForSpeakerId(m.agentSpeakerId(), scene)
                        + " 的追问「" + q + "」——解读时必须带上该追问的完整语境（尤其是其中的假设前提）；"
                        + "用户对**假设情境**的选择不等于改变其已确认的当下行动。";
            }
        }
        return null;
    }

    /**
     * 全场已提出过的追问（各发言以 ❓ 开头的行）。确定性提取后注入上下文并明令禁止重复——
     * 仅靠"不要重复提问"的软指令不够：2026-06-12 实测中场小结原样复述了上一位发言人刚问过、
     * 用户甚至已经回答了的问题。
     */
    private static String formatAskedQuestions(List<ConversationMessage> prior) {
        List<String> questions = new ArrayList<>();
        for (ConversationMessage m : prior) {
            if (!isDeliberationAgentMessage(m)) {
                continue;
            }
            for (String rawLine : m.content().split("\n")) {
                String line = rawLine.trim();
                if (line.startsWith("❓")) {
                    String q = line.substring(1).trim().replaceAll("[*_`]", "");
                    if (q.length() > 120) {
                        q = q.substring(0, 120) + "…";
                    }
                    if (!q.isEmpty() && !questions.contains(q)) {
                        questions.add(q);
                    }
                }
            }
        }
        if (questions.isEmpty()) {
            return "";
        }
        if (questions.size() > 15) {
            questions = questions.subList(questions.size() - 15, questions.size());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            sb.append(i + 1).append(". ").append(questions.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    /** 会话中第一条用户消息 = 沙盘要钉死的「核心议题」（后续轮次仍带回，避免攻击飘成套话） */
    private static String firstUserIssue(List<ConversationMessage> prior, String latestUser) {
        for (ConversationMessage m : prior) {
            if (m.role() != MessageRole.USER || m.content() == null) {
                continue;
            }
            String t = m.content().trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return latestUser == null ? "" : latestUser.trim();
    }

    private static String truncateCoreTopic(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String t = raw.trim();
        if (t.length() <= SANDBOX_CORE_TOPIC_MAX_CHARS) {
            return t;
        }
        return t.substring(0, SANDBOX_CORE_TOPIC_MAX_CHARS) + "\n…（议题过长已截断）";
    }

    /**
     * 内置四席共用：审议场景加权 + 阶段说明 +「核心议题 + 本轮用户话 + 已发言观点」，再由各角色指令收尾。
     */
    private String formatSandboxContextBlock(
            List<ConversationMessage> prior,
            String latestUser,
            SandboxDeliberationScene scene,
            String phaseBlock
    ) {
        String routing = SandboxSceneRouting.contextBlockFor(scene).trim();
        String phase = phaseBlock == null ? "" : phaseBlock.trim();
        String core = truncateCoreTopic(firstUserIssue(prior, latestUser));
        String latest = latestUser == null ? "" : latestUser.trim();
        String claimsTable = formatPriorClaimsTable(prior, scene);
        String agents = formatPriorAgents(prior, scene);
        StringBuilder sb = new StringBuilder();
        sb.append(routing).append("\n\n");
        if (!phase.isEmpty()) {
            sb.append(phase).append("\n\n");
        }
        String userFacts = formatUserFacts(prior, scene);
        sb.append("## 用户核心议题（整场沙盘须围绕此议题，禁止空泛套话）\n\n")
                .append(core.isEmpty() ? "（用户尚未说明具体议题）" : core);
        if (!userFacts.isEmpty()) {
            sb.append("\n\n## 用户在审议中已补充的事实（按时间序，这些是用户的真实处境，分析与追问必须建立在其上）\n\n")
                    .append(userFacts);
        }
        sb.append("\n\n## 本轮用户最新发言\n\n")
                .append(latest.isEmpty() ? "（无）" : latest);
        if (!latest.isEmpty()) {
            String binding = latestAnswerBinding(prior, scene);
            if (binding != null) {
                sb.append("\n\n").append(binding);
            }
        }
        if (!claimsTable.isEmpty()) {
            sb.append("\n\n## 前序观点速览（交叉审查时从此表选定交锋对象与具体论断）\n\n")
                    .append(claimsTable);
        }
        String askedQuestions = formatAskedQuestions(prior);
        if (!askedQuestions.isEmpty()) {
            sb.append("\n\n## 已提出过的追问（你的新问题不得与其中任何一条重复或近似；用户对它们的回答见上方事实清单）\n\n")
                    .append(askedQuestions);
        }
        sb.append("\n\n## 前面其他参与者的完整发言\n\n")
                .append(agents.isEmpty() ? "（暂无）" : agents)
                .append("\n");
        return sb.toString();
    }

    private String buildAttackerUserMessage(
            List<ConversationMessage> prior,
            String latestUser,
            SandboxDeliberationScene scene,
            String phaseBlock
    ) {
        String isIndependentAnalysis = phaseBlock != null && phaseBlock.contains("独立分析") ? "是" : "否";
        String msg = formatSandboxContextBlock(prior, latestUser, scene, phaseBlock)
                + "\n---\n\n现在轮到你了。从**你的角色立场**出发，**直指上文「用户核心议题」中的具体目标、约束、利益相关方、时间或内在矛盾**发起挑战或追问；"
                + "须遵守上文「本轮审议阶段」：独立分析轮匿名化引用；交叉审查轮必须点名交锋并尝试合题。"
                + "禁止只输出与用户议题无关的通用管理话术。"
                + "\n\n接地要求（违反任何一条即视为失败发言）：\n"
                + "1. 你的分析必须**显式使用**「用户在审议中已补充的事实」（如有）——引用用户原话关键词，把它们当作已确认的真实处境，而不是从零空谈；\n"
                + "2. **禁止重复询问**用户已经回答过的问题（已回答内容见上方事实清单）；新的追问必须比上一轮更深一层，建立在已有回答之上；\n"
                + "3. 若用户最新发言是对某位发言人追问的回答，你必须先一句话点明「这个回答改变了什么判断」，再展开你的分析。";
        if ("是".equals(isIndependentAnalysis)) {
            msg += "\n\n**首先**用一句话总结你的核心观点/建议方向（便于读者快速抓住重点），然后按你的指定输出格式完整展开分析。";
        }
        return msg + "\n";
    }

    /** 取自上一次整合官收口之后的全部审议发言（即本圈待综合的各角色分析），供共识评估 */
    private static List<String> lapAnalysesForGate(List<ConversationMessage> prior) {
        List<String> out = new ArrayList<>();
        for (int i = prior.size() - 1; i >= 0; i--) {
            ConversationMessage m = prior.get(i);
            if ("integrator".equals(m.agentSpeakerId())) {
                break;
            }
            if (isDeliberationAgentMessage(m)) {
                out.add(m.content().trim());
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /** 把共识评估转成整合官的收口策略指令 */
    private static String consensusBlock(AdaptiveDepthGate.ConsensusAssessment a) {
        if (a == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n## 共识评估（收口策略依据；不要在报告中原样照抄本节字段）\n\n");
        sb.append("- 共识级别：").append(a.consensusLevel).append("\n");
        if (a.majorityView != null && !a.majorityView.isBlank()) {
            sb.append("- 多数方向：").append(a.majorityView).append("\n");
        }
        if (a.minorityView != null && !a.minorityView.isBlank()) {
            sb.append("- 少数观点：").append(a.minorityView);
            if (a.minorityStrength != null && !a.minorityStrength.isBlank()) {
                sb.append("（强度：").append(a.minorityStrength).append("）");
            }
            sb.append("\n");
        }
        sb.append("\n收口要求：\n");
        if (a.isHighConsensus()) {
            sb.append("- 各角色方向高度一致：报告**第一句**明确告知用户共识所在，直接给出结论与行动清单；")
                    .append("不要为了表面平衡人为制造分歧。\n");
        } else {
            sb.append("- 存在实质分歧：报告末尾必须加「## 主要分歧与下一步」小节，点名列出最关键的 1~2 处分歧，")
                    .append("并明确建议用户**补充哪类具体信息**后继续对话，进入下一轮交叉审查把分歧打透。\n");
        }
        return sb.toString();
    }

    /** 用户是否明确要求提前收束出报告（中场小结里会提示这个口令） */
    private static boolean userRequestedCloseout(String latestUser) {
        if (latestUser == null || latestUser.isBlank()) {
            return false;
        }
        String t = latestUser.trim();
        return t.contains("给出结论") || t.contains("出结论") || t.contains("出报告")
                || t.contains("最终报告") || t.contains("收束") || t.contains("直接总结")
                || t.contains("给我建议") || t.contains("最终建议");
    }

    /** 中场小结的 user 消息：完整上下文 + 共识评估，但任务是小结与引导，不是终局报告 */
    private String buildInterimUserMessage(
            List<ConversationMessage> prior,
            String latestUser,
            SandboxDeliberationScene scene,
            AdaptiveDepthGate.ConsensusAssessment assessment
    ) {
        String phase = """
                ## 本轮审议阶段：中场小结（第一圈结束，共识未达成）

                不要输出终局报告；按你的角色指令做紧凑小结，并把审议引向第二圈交叉审查。
                """;
        StringBuilder facts = new StringBuilder("\n## 共识评估（小结依据，不要原样照抄字段）\n\n");
        if (assessment != null) {
            facts.append("- 共识级别：").append(assessment.consensusLevel).append("\n");
            if (assessment.majorityView != null && !assessment.majorityView.isBlank()) {
                facts.append("- 多数方向：").append(assessment.majorityView).append("\n");
            }
            if (assessment.minorityView != null && !assessment.minorityView.isBlank()) {
                facts.append("- 少数观点：").append(assessment.minorityView).append("\n");
            }
        }
        return formatSandboxContextBlock(prior, latestUser, scene, phase)
                + facts
                + "\n---\n请基于上述共识评估完成中场小结：当前共识、最大分歧（点名出处）、当前最值得用户回答的一个新问题。\n"
                + "注意：用户最新发言若已回答了某位发言人的追问，必须把该回答吸收进共识/分歧的表述；"
                + "你的 ❓ 严禁与「已提出过的追问」清单中任何一条重复或近似——若分歧已被用户的回答化解，就指出审议该往哪个新方向去。\n";
    }

    private String buildIntegratorUserMessage(
            List<ConversationMessage> prior,
            String latestUser,
            SandboxDeliberationScene scene,
            String phaseBlock,
            AdaptiveDepthGate.ConsensusAssessment assessment
    ) {
        return formatSandboxContextBlock(prior, latestUser, scene, phaseBlock)
                + consensusBlock(assessment)
                + "\n---\n作为本轮综合执笔人，请收束这场博弈：博弈复盘与决策沙盘表格中的「关键问题」必须**显式回扣上述用户核心议题**，"
                + "结论与行动建议必须**逐条对应「用户在审议中已补充的事实」**（如有）——用户给过的数字、约束、表态都要用上，"
                + "不允许给出对任何人都成立的通用建议。输出你的决策沙盘报告。\n";
    }

    public Map<String, Object> capabilities() {
        return Map.of(
                "firstParty", Map.of("agentId", mainAgent.agentId(), "mode", "calibration"),
                "sandbox", Map.of(
                        "mode", "sandbox",
                        "turnTaking", true,
                        "builtIn", List.of("auditor", "risk_officer", "value_judge", "integrator")
                )
        );
    }

    public Optional<String> resolveRouteHint(String mode) {
        if ("SANDBOX".equalsIgnoreCase(mode)) {
            return Optional.of("sandbox");
        }
        return Optional.of("first-party");
    }
}
