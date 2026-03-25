package com.questionos.backend.agent;

import com.questionos.backend.integrations.AgentRegistryService;
import com.questionos.backend.integrations.OpenClawInvokeService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Component
public class ThirdPartyAgentAdapter implements AgentExecutor {
    private final AgentRegistryService registryService;
    private final OpenClawInvokeService invokeService;

    public ThirdPartyAgentAdapter(AgentRegistryService registryService, OpenClawInvokeService invokeService) {
        this.registryService = registryService;
        this.invokeService = invokeService;
    }

    @Override
    public String agentId() {
        return "third-party-adapter";
    }

    @Override
    public Flux<AgentReplyChunk> reply(String sessionId, long turnId, String input) {
        return Flux.defer(() -> registryService.firstAvailableAgent()
                .map(agent -> Flux.just(new AgentReplyChunk("agent_start", "third-party-adapter|第三方接入Agent"))
                        .concatWith(
                                invokeService.invokeAgent(agent, sessionId, turnId, input)
                                        .flatMapMany(text -> Flux.just(
                                                new AgentReplyChunk("agent_chunk", text),
                                                new AgentReplyChunk("agent_done", "第三方Agent阶段结束。")
                                        ))
                                        .onErrorResume(e -> Flux.just(
                                                new AgentReplyChunk("agent_error", "第三方Agent调用失败: " + e.getMessage()),
                                                new AgentReplyChunk("agent_done", "第三方Agent阶段结束。")
                                        ))
                        ))
                .orElseGet(() -> Flux.just(
                        new AgentReplyChunk("agent_done", "第三方Agent阶段结束。")
                )))
                .delayElements(Duration.ofMillis(120));
    }

    public boolean hasAvailableAgent() {
        return registryService.firstAvailableAgentSummary().isPresent();
    }

}
