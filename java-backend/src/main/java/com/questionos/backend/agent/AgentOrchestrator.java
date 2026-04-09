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

    private enum SandboxSlot {
        THIRD_PARTY,
        AUDITOR,
        RISK_OFFICER,
        VALUE_JUDGE,
        INTEGRATOR
    }

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
            int sandboxRoundIndex
    ) {
        if (mode == SessionMode.CALIBRATION) {
            return Flux.just(new AgentReplyChunk("agent_start", "main-calibrate|主校准 Agent"))
                    .concatWith(mainAgent.replyWithHistory(sessionId, turnId, input, history))
                    .concatWithValues(new AgentReplyChunk("done", "本轮结束"));
        }
        return sandboxSingleReply(history, sandboxRoundIndex).concatWithValues(new AgentReplyChunk("done", "本轮结束"));
    }

    private Flux<AgentReplyChunk> sandboxSingleReply(List<ConversationMessage> history, int sandboxRoundIndex) {
        Optional<AgentRegistryService.RegisteredAgent> reg = registryService.firstAvailableAgent();
        List<SandboxSlot> order = new ArrayList<>();
        boolean hasThirdPartyAgents = reg.isPresent();

        // 你的新规则：
        // - 未接入任何三方 agents：只运行内置四角色
        // - 已接入三方 agents：内置四角色 + 三方 slot 轮转
        if (hasThirdPartyAgents) {
            // 三方 slot 出现两次，提高权重；其余内置各一次
            order.add(SandboxSlot.THIRD_PARTY);
            order.add(SandboxSlot.AUDITOR);
            order.add(SandboxSlot.THIRD_PARTY);
            order.add(SandboxSlot.RISK_OFFICER);
            order.add(SandboxSlot.VALUE_JUDGE);
            order.add(SandboxSlot.INTEGRATOR);
        } else {
            order.add(SandboxSlot.AUDITOR);
            order.add(SandboxSlot.RISK_OFFICER);
            order.add(SandboxSlot.VALUE_JUDGE);
            order.add(SandboxSlot.INTEGRATOR);
        }

        SandboxSlot slot = order.get(Math.floorMod(sandboxRoundIndex, order.size()));
        String latestUser = latestUserMessage(history);
        List<ConversationMessage> prior = priorHistory(history);

        return switch (slot) {
            case THIRD_PARTY -> {
                // slot 不会在 hasThirdPartyAgents=false 时出现
                AgentRegistryService.RegisteredAgent agent = reg.get();
                String aid = agent.agentId();
                yield oneSpeakerWithAgent(
                        aid,
                        aid,
                        null,
                        buildThirdPartyUserMessage(prior, latestUser),
                        agent,
                        aid + " 发言结束。"
                );
            }
            case AUDITOR -> hasThirdPartyAgents
                    ? oneSpeakerWithAgent(
                            "auditor",
                            "利益审计师",
                            SandboxBuiltInPrompts.AUDITOR,
                            buildAttackerUserMessage(prior, latestUser),
                            reg.get(),
                            "利益审计师 发言结束。"
                    )
                    : oneSpeakerWithDefaultLlm(
                            "auditor",
                            "利益审计师",
                            SandboxBuiltInPrompts.AUDITOR,
                            buildAttackerUserMessage(prior, latestUser),
                            "利益审计师 发言结束。"
                    );
            case RISK_OFFICER -> hasThirdPartyAgents
                    ? oneSpeakerWithAgent(
                            "risk_officer",
                            "风险预测官",
                            SandboxBuiltInPrompts.RISK_OFFICER,
                            buildAttackerUserMessage(prior, latestUser),
                            reg.get(),
                            "风险预测官 发言结束。"
                    )
                    : oneSpeakerWithDefaultLlm(
                            "risk_officer",
                            "风险预测官",
                            SandboxBuiltInPrompts.RISK_OFFICER,
                            buildAttackerUserMessage(prior, latestUser),
                            "风险预测官 发言结束。"
                    );
            case VALUE_JUDGE -> hasThirdPartyAgents
                    ? oneSpeakerWithAgent(
                            "value_judge",
                            "价值裁判",
                            SandboxBuiltInPrompts.VALUE_JUDGE,
                            buildAttackerUserMessage(prior, latestUser),
                            reg.get(),
                            "价值裁判 发言结束。"
                    )
                    : oneSpeakerWithDefaultLlm(
                            "value_judge",
                            "价值裁判",
                            SandboxBuiltInPrompts.VALUE_JUDGE,
                            buildAttackerUserMessage(prior, latestUser),
                            "价值裁判 发言结束。"
                    );
            case INTEGRATOR -> hasThirdPartyAgents
                    ? oneSpeakerWithAgent(
                            "integrator",
                            "首席整合官",
                            SandboxBuiltInPrompts.INTEGRATOR,
                            buildIntegratorUserMessage(prior, latestUser),
                            reg.get(),
                            "首席整合官 发言结束。"
                    )
                    : oneSpeakerWithDefaultLlm(
                            "integrator",
                            "首席整合官",
                            SandboxBuiltInPrompts.INTEGRATOR,
                            buildIntegratorUserMessage(prior, latestUser),
                            "首席整合官 发言结束。"
                    );
        };
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

    private String displayNameForSpeakerId(String id) {
        if (id == null) return "助手";
        return switch (id) {
            case "auditor" -> "利益审计师";
            case "risk_officer" -> "风险预测官";
            case "value_judge" -> "价值裁判";
            case "integrator" -> "首席整合官";
            case "third-party-adapter" -> "外聘 Agent";
            default -> registryService.find(id).map(AgentRegistryService.RegisteredAgent::agentId).orElse(id);
        };
    }

    private String formatPriorAgents(List<ConversationMessage> prior) {
        StringBuilder sb = new StringBuilder();
        for (ConversationMessage m : prior) {
            if (m.role() != MessageRole.AGENT || m.content() == null || m.content().isBlank()) continue;
            String label = displayNameForSpeakerId(m.agentSpeakerId());
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
     * 内置/外聘共用：把「核心议题 + 本轮用户话 + 已发言观点」拼成一块，再由各角色指令收尾。
     */
    private String formatSandboxContextBlock(List<ConversationMessage> prior, String latestUser) {
        String core = truncateCoreTopic(firstUserIssue(prior, latestUser));
        String latest = latestUser == null ? "" : latestUser.trim();
        String agents = formatPriorAgents(prior);
        return "## 用户核心议题（整场沙盘须围绕此议题，禁止空泛套话）\n\n"
                + (core.isEmpty() ? "（用户尚未说明具体议题）" : core)
                + "\n\n## 本轮用户最新发言\n\n"
                + (latest.isEmpty() ? "（无）" : latest)
                + "\n\n## 前面其他参与者的观点\n\n"
                + (agents.isEmpty() ? "（暂无）" : agents)
                + "\n";
    }

    private String buildAttackerUserMessage(List<ConversationMessage> prior, String latestUser) {
        return formatSandboxContextBlock(prior, latestUser)
                + "\n---\n\n现在轮到你了。从**你的角色立场**出发，**直指上文「用户核心议题」中的具体目标、约束、利益相关方、时间或内在矛盾**发起挑战或追问；"
                + "可引用「前面其他参与者」的论点与之交锋。"
                + "禁止只输出与用户议题无关的通用管理话术。\n";
    }

    private String buildIntegratorUserMessage(List<ConversationMessage> prior, String latestUser) {
        return formatSandboxContextBlock(prior, latestUser)
                + "\n---\n作为首席整合官，请收束这场博弈：博弈复盘与决策沙盘表格中的「关键问题」必须**显式回扣上述用户核心议题**，"
                + "输出你的决策沙盘报告。\n";
    }

    private String buildThirdPartyUserMessage(List<ConversationMessage> prior, String latestUser) {
        return formatSandboxContextBlock(prior, latestUser)
                + "\n---\n请结合以上语境（尤其用户核心议题），从你的能力出发补充、质疑或给出一记「外视角」追问。\n";
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
