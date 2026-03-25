package com.questionos.backend.api;

import com.questionos.backend.integrations.AgentRegistryService;
import com.questionos.backend.governance.AuditService;
import com.questionos.backend.integrations.OpenClawInvokeService;
import com.questionos.backend.service.SessionService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    private final AgentRegistryService registryService;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final OpenClawInvokeService invokeService;

    public AgentController(AgentRegistryService registryService, SessionService sessionService, AuditService auditService, OpenClawInvokeService invokeService) {
        this.registryService = registryService;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.invokeService = invokeService;
    }

    public record RegisterAgentRequest(
            @NotBlank String agentId,
            @NotBlank String provider,
            @NotBlank String endpoint,
            String scope,
            String apiKey,
            String model
    ) {}
    public record RegisterAgentResponse(String agentId, String status) {}

    @PostMapping("/register")
    public ResponseEntity<RegisterAgentResponse> register(@RequestBody RegisterAgentRequest request, ServerHttpRequest httpRequest) {
        registryService.register(
                request.agentId(),
                request.provider(),
                request.endpoint(),
                request.scope() == null ? "sandbox:invoke" : request.scope(),
                request.apiKey(),
                request.model()
        );
        auditService.record(httpRequest.getId(), "partner", "agent_register", request.agentId(), "ok");
        return ResponseEntity.ok(new RegisterAgentResponse(request.agentId(), "registered"));
    }

    @GetMapping("/capabilities")
    public ResponseEntity<Map<String, Object>> capabilities() {
        return ResponseEntity.ok(sessionService.capabilities());
    }

    @GetMapping("/instances")
    public ResponseEntity<Map<String, Object>> instances() {
        List<Map<String, Object>> items = registryService.all().stream()
                .sorted(Comparator.comparing(AgentRegistryService.RegisteredAgent::registeredAt).reversed())
                .map(a -> Map.<String, Object>of(
                        "agentId", a.agentId(),
                        "provider", a.provider(),
                        "endpoint", a.endpoint(),
                        "scope", a.scope(),
                        "model", a.model() == null ? "" : a.model(),
                        "registeredAt", a.registeredAt().toString()
                ))
                .toList();
        return ResponseEntity.ok(Map.of(
                "count", items.size(),
                "instances", items
        ));
    }

    @PostMapping("/{agentId}/invoke")
    public Mono<ResponseEntity<Map<String, Object>>> invoke(@PathVariable String agentId, @RequestBody Map<String, Object> payload, ServerHttpRequest httpRequest) {
        return registryService.find(agentId)
                .map(agent -> {
                    String sessionId = String.valueOf(payload.getOrDefault("sessionId", "sess_manual"));
                    Object turnObj = payload.getOrDefault("turnId", 0);
                    long turnId = turnObj instanceof Number n ? n.longValue() : 0L;
                    String input = String.valueOf(payload.getOrDefault("input", ""));
                    return invokeService.invokeAgent(agent, sessionId, turnId, input)
                            .map(output -> {
                                auditService.record(httpRequest.getId(), "partner", "agent_invoke", agentId, "ok");
                                return ResponseEntity.ok(Map.<String, Object>of(
                                        "agentId", agent.agentId(),
                                        "status", "accepted",
                                        "input", input,
                                        "output", output
                                ));
                            })
                            .onErrorResume(e -> {
                                auditService.record(httpRequest.getId(), "partner", "agent_invoke", agentId, "failed");
                                return Mono.just(ResponseEntity.status(502).body(Map.<String, Object>of(
                                        "agentId", agent.agentId(),
                                        "status", "failed",
                                        "input", input,
                                        "error", "第三方Agent调用失败: " + e.getMessage()
                                )));
                            });
                })
                .orElseGet(() -> Mono.just(ResponseEntity.notFound().build()));
    }

    @GetMapping("/audit")
    public ResponseEntity<?> audit() {
        return ResponseEntity.ok(auditService.latest(100));
    }
}
