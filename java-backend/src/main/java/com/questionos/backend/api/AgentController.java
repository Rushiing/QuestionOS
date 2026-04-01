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
@CrossOrigin(
        originPatterns = {"https://*.up.railway.app", "http://localhost:*", "http://127.0.0.1:*"},
        allowedHeaders = {"Authorization", "Content-Type", "X-API-Version", "Idempotency-Key", "Last-Event-ID", "Accept", "Origin"},
        methods = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS},
        allowCredentials = "false"
)
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

    @GetMapping("/onboarding-packet")
    public ResponseEntity<Map<String, Object>> onboardingPacket(ServerHttpRequest request) {
        String baseUrl = baseUrl(request);
        return ResponseEntity.ok(Map.of(
                "version", "qos-agent-onboarding/v1",
                "goal", "让 OpenClaw Agent 自动完成 QuestionOS 三方接入并验证",
                "questionos", Map.of(
                        "baseUrl", baseUrl,
                        "capabilitiesUrl", baseUrl + "/api/v1/agents/capabilities",
                        "registerUrl", baseUrl + "/api/v1/agents/register",
                        "instancesUrl", baseUrl + "/api/v1/agents/instances",
                        "probeTemplate", Map.of(
                                "invokeUrlTemplate", baseUrl + "/api/v1/agents/{agentId}/invoke",
                                "input", "请回复：联通成功"
                        )
                ),
                "registerPayloadSchema", Map.of(
                        "agentId", "string, 建议唯一（如 openclaw-xxxxxx）",
                        "provider", "OpenClaw",
                        "endpoint", "OpenAI-compatible base url，例如 https://xxx 或 https://xxx/v1",
                        "scope", "sandbox:invoke",
                        "apiKey", "string, 可为空（若上游无需鉴权）",
                        "model", "string, 可选"
                ),
                "successCriteria", List.of(
                        "注册接口返回 status=registered",
                        "instances 列表包含新 agentId",
                        "invoke 探活返回 status=accepted 且 output 非空"
                ),
                "securityNote", "写入 apiKey 前请先进行用户确认；禁止把密钥回显到公开聊天。"
        ));
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

    private static String baseUrl(ServerHttpRequest request) {
        var uri = request.getURI();
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        String host = uri.getHost() == null ? "localhost" : uri.getHost();
        int port = uri.getPort();
        if (port <= 0) {
            return scheme + "://" + host;
        }
        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }
}
