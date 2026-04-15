package com.questionos.backend.agent;

import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.MessageRole;
import com.questionos.backend.domain.SessionMode;
import com.questionos.backend.integrations.AgentRegistryService;
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
    private final AgentRegistryService registryService;
    private final OpenClawInvokeService invokeService;

    public AgentOrchestrator(
            MainCalibrateAgent mainAgent,
            AgentRegistryService registryService,
            OpenClawInvokeService invokeService
    ) {
        this.mainAgent = mainAgent;
        this.registryService = registryService;
        this.invokeService = invokeService;
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
        Optional<AgentRegistryService.RegisteredAgent> reg = registryService.firstAvailableAgent();
        boolean hasThirdPartyAgents = reg.isPresent();
        SandboxDeliberationScene scene = SandboxDeliberationScene.parseStored(sandboxDeliberationSceneRaw);
        List<SandboxAgoraTurnPlan.BuiltinTurn> plan = SandboxAgoraTurnPlan.fourBuiltin(scene);

        record Step(boolean thirdParty, int builtinIdx) {}
        List<Step> steps = new ArrayList<>();
        if (hasThirdPartyAgents) {
            steps.add(new Step(true, -1));
            steps.add(new Step(false, 0));
            steps.add(new Step(true, -1));
            steps.add(new Step(false, 1));
            steps.add(new Step(false, 2));
            steps.add(new Step(false, 3));
        } else {
            steps.add(new Step(false, 0));
            steps.add(new Step(false, 1));
            steps.add(new Step(false, 2));
            steps.add(new Step(false, 3));
        }
        int cycleLen = steps.size();
        Step current = steps.get(Math.floorMod(sandboxRoundIndex, cycleLen));
        String latestUser = latestUserMessage(history);
        List<ConversationMessage> prior = priorHistory(history);

        if (current.thirdParty()) {
            AgentRegistryService.RegisteredAgent agent = reg.get();
            String aid = agent.agentId();
            String phaseBlock = deliberationPhaseBlock(SandboxSlot.THIRD_PARTY, sandboxRoundIndex, cycleLen, prior);
            return oneSpeakerWithAgent(
                    aid,
                    aid,
                    null,
                    buildThirdPartyUserMessage(prior, latestUser, scene, phaseBlock),
                    agent,
                    aid + " 发言结束。"
            );
        }
        SandboxAgoraTurnPlan.BuiltinTurn b = plan.get(current.builtinIdx());
        String phaseBlock = deliberationPhaseBlock(b.slot(), sandboxRoundIndex, cycleLen, prior);
        String speakerId = speakerIdForSlot(b.slot());
        String sys = augmentBuiltinSystemPrompt(b);
        String done = b.displayName() + " 发言结束。";
        if (b.slot() == SandboxSlot.INTEGRATOR) {
            if (hasThirdPartyAgents) {
                return oneSpeakerWithAgent(
                        speakerId,
                        b.displayName(),
                        sys,
                        buildIntegratorUserMessage(prior, latestUser, scene, phaseBlock),
                        reg.get(),
                        done
                );
            }
            return oneSpeakerWithDefaultLlm(
                    speakerId,
                    b.displayName(),
                    sys,
                    buildIntegratorUserMessage(prior, latestUser, scene, phaseBlock),
                    done
            );
        }
        if (hasThirdPartyAgents) {
            return oneSpeakerWithAgent(
                    speakerId,
                    b.displayName(),
                    sys,
                    buildAttackerUserMessage(prior, latestUser, scene, phaseBlock),
                    reg.get(),
                    done
            );
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

                    先用各半句点明前文中最尖锐的「正题 / 反题」张力，再按既定 Markdown 输出；博弈复盘三条须能看出与该张力对应。
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

                - 至少点名一位前序参与者（用其在界面上的**展示名/外聘 Agent 名**），针对其**一条**具体论断表明赞成或反对及理由。
                - 用 1 句话给出合题取向：在什么前提下双方可部分同时成立。
                - 为完成论证，总字数允许在约 220 字内，仍以可验证的追问或行动收口。
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

    private Flux<AgentReplyChunk> oneSpeakerWithAgent(
            String speakerId,
            String displayName,
            String systemPrompt,
            String userMessage,
            AgentRegistryService.RegisteredAgent agent,
            String doneLine
    ) {
        return Flux.just(new AgentReplyChunk("agent_start", speakerId + "|" + displayName))
                .concatWith(
                        // 不设外层短超时：OpenClawInvokeService 已按 questionos.llm.timeoutSeconds 约束整段调用
                        invokeService.invokeOpenClaw(agent, systemPrompt, userMessage)
                                .flatMapMany(text -> Flux.just(new AgentReplyChunk("agent_chunk", text)))
                                .onErrorResume(e -> Flux.just(
                                        new AgentReplyChunk("agent_error", "调用失败: " + e.getMessage()),
                                        new AgentReplyChunk("agent_chunk", fallbackLine(speakerId))
                                ))
                )
                .concatWithValues(new AgentReplyChunk("agent_done", doneLine))
                .delayElements(Duration.ofMillis(80));
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
            case "third-party-adapter" -> "外聘 Agent";
            default -> registryService.find(id).map(AgentRegistryService.RegisteredAgent::agentId).orElse(id);
        };
    }

    private String formatPriorAgents(List<ConversationMessage> prior, SandboxDeliberationScene scene) {
        StringBuilder sb = new StringBuilder();
        for (ConversationMessage m : prior) {
            if (m.role() != MessageRole.AGENT || m.content() == null || m.content().isBlank()) {
                continue;
            }
            if ("sandbox-route".equals(m.agentSpeakerId()) || "sandbox-classify".equals(m.agentSpeakerId())) {
                continue;
            }
            String label = displayNameForSpeakerId(m.agentSpeakerId(), scene);
            sb.append("【").append(label).append("】：").append(m.content().trim()).append("\n\n");
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
     * 内置/外聘共用：审议场景加权 + 阶段说明 +「核心议题 + 本轮用户话 + 已发言观点」，再由各角色指令收尾。
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
        String agents = formatPriorAgents(prior, scene);
        StringBuilder sb = new StringBuilder();
        sb.append(routing).append("\n\n");
        if (!phase.isEmpty()) {
            sb.append(phase).append("\n\n");
        }
        sb.append("## 用户核心议题（整场沙盘须围绕此议题，禁止空泛套话）\n\n")
                .append(core.isEmpty() ? "（用户尚未说明具体议题）" : core)
                .append("\n\n## 本轮用户最新发言\n\n")
                .append(latest.isEmpty() ? "（无）" : latest)
                .append("\n\n## 前面其他参与者的观点\n\n")
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
        return formatSandboxContextBlock(prior, latestUser, scene, phaseBlock)
                + "\n---\n\n现在轮到你了。从**你的角色立场**出发，**直指上文「用户核心议题」中的具体目标、约束、利益相关方、时间或内在矛盾**发起挑战或追问；"
                + "须遵守上文「本轮审议阶段」：独立分析轮匿名化引用；交叉审查轮必须点名交锋并尝试合题。"
                + "禁止只输出与用户议题无关的通用管理话术。\n";
    }

    private String buildIntegratorUserMessage(
            List<ConversationMessage> prior,
            String latestUser,
            SandboxDeliberationScene scene,
            String phaseBlock
    ) {
        return formatSandboxContextBlock(prior, latestUser, scene, phaseBlock)
                + "\n---\n作为本轮综合执笔人，请收束这场博弈：博弈复盘与决策沙盘表格中的「关键问题」必须**显式回扣上述用户核心议题**，"
                + "输出你的决策沙盘报告。\n";
    }

    private String buildThirdPartyUserMessage(
            List<ConversationMessage> prior,
            String latestUser,
            SandboxDeliberationScene scene,
            String phaseBlock
    ) {
        return formatSandboxContextBlock(prior, latestUser, scene, phaseBlock)
                + "\n---\n请结合以上语境（尤其用户核心议题），从你的能力出发补充、质疑或给出一记「外视角」追问；"
                + "遵守上文「本轮审议阶段」对独立分析/交叉审查的约束。\n";
    }

    public Map<String, Object> capabilities() {
        return Map.of(
                "firstParty", Map.of("agentId", mainAgent.agentId(), "mode", "calibration"),
                "sandbox", Map.of(
                        "mode", "sandbox",
                        "turnTaking", true,
                        "builtIn", List.of("auditor", "risk_officer", "value_judge", "integrator"),
                        "thirdPartySlot", "2-of-6-rotation"
                )
        );
    }

    public Optional<String> resolveRouteHint(String mode) {
        if ("SANDBOX".equalsIgnoreCase(mode)) {
            return Optional.of("third-party");
        }
        return Optional.of("first-party");
    }
}
