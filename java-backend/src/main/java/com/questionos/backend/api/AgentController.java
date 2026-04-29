package com.questionos.backend.api;

import com.questionos.backend.integrations.AgentRegistryService;
import com.questionos.backend.governance.AuditService;
import com.questionos.backend.integrations.OpenClawInvokeService;
import com.questionos.backend.service.OnboardingJobService;
import com.questionos.backend.service.SessionService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
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
    private final OnboardingJobService onboardingJobService;

    public AgentController(AgentRegistryService registryService, SessionService sessionService, AuditService auditService, OpenClawInvokeService invokeService, OnboardingJobService onboardingJobService) {
        this.registryService = registryService;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.invokeService = invokeService;
        this.onboardingJobService = onboardingJobService;
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
    public record CreateOnboardingJobResponse(
            String jobId,
            String submitToken,
            String status,
            String instructionUrl,
            String submitUrl,
            String statusUrl
    ) {}
    public record SubmitOnboardingJobRequest(
            @NotBlank String submitToken,
            @NotBlank String agentId,
            @NotBlank String provider,
            @NotBlank String endpoint,
            String scope,
            String apiKey,
            String model,
            Boolean runProbe
    ) {}

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

    @PostMapping("/onboarding-jobs")
    public ResponseEntity<CreateOnboardingJobResponse> createOnboardingJob(ServerHttpRequest request) {
        var job = onboardingJobService.create();
        String base = baseUrl(request);
        return ResponseEntity.ok(new CreateOnboardingJobResponse(
                job.jobId(),
                job.submitToken(),
                job.status().name(),
                base + "/api/v1/agents/onboarding-jobs/" + job.jobId(),
                base + "/api/v1/agents/onboarding-jobs/" + job.jobId() + "/submit",
                base + "/api/v1/agents/onboarding-jobs/" + job.jobId() + "/status"
        ));
    }

    @PostMapping("/delegate")
    public ResponseEntity<Map<String, Object>> delegateAgentOnboarding() {
        var job = onboardingJobService.create();
        return ResponseEntity.ok(Map.of(
                "status", "pending",
                "jobId", job.jobId(),
                "submitToken", job.submitToken(),
                "message", "委托任务已创建，Agent 将自动完成接入流程"
        ));
    }

    @GetMapping("/onboarding-jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> onboardingJobInstruction(@PathVariable String jobId, ServerHttpRequest request) {
        return onboardingJobService.find(jobId)
                .map(job -> {
                    String base = baseUrl(request);
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "version", "qos-agent-onboarding-job/v1",
                            "jobId", job.jobId(),
                            "goal", "请你作为 OpenClaw 接入代理，自动完成注册并探活。",
                            "auth", Map.of("submitToken", job.submitToken()),
                            "endpoints", Map.of(
                                    "submit", base + "/api/v1/agents/onboarding-jobs/" + job.jobId() + "/submit",
                                    "status", base + "/api/v1/agents/onboarding-jobs/" + job.jobId() + "/status",
                                    "capabilities", base + "/api/v1/agents/capabilities",
                                    "instances", base + "/api/v1/agents/instances"
                            ),
                            "submitPayloadSchema", Map.of(
                                    "submitToken", "required",
                                    "agentId", "required",
                                    "provider", "required, OpenClaw",
                                    "endpoint", "required",
                                    "scope", "optional, default sandbox:invoke",
                                    "apiKey", "REQUIRED if your endpoint enforces Bearer auth — pass YOUR endpoint's API key here so QuestionOS can call you back. Leave empty only if endpoint accepts unauthenticated requests.",
                                    "model", "optional",
                                    "runProbe", "optional, default true"
                            ),
                            "successCriteria", List.of(
                                    "status 最终为 VERIFIED",
                                    "message 包含联通成功"
                            ),
                            "commonFailures", List.of(
                                    "如果探活返回 LLM HTTP 401: Unauthorized → 你提交时漏填了 apiKey，但你的 endpoint 要求 Bearer auth。重新 submit 并填入正确的 apiKey 即可。",
                                    "如果探活返回 LLM HTTP 408: Request Timeout → 你的 endpoint 没有及时响应。检查你的服务是否在线。"
                            )
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/onboarding-jobs/{jobId}/submit")
    public Mono<ResponseEntity<Map<String, Object>>> submitOnboardingJob(
            @PathVariable String jobId,
            @RequestBody SubmitOnboardingJobRequest request,
            ServerHttpRequest httpRequest
    ) {
        var existing = onboardingJobService.find(jobId);
        if (existing.isEmpty()) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        if (!existing.get().submitToken().equals(request.submitToken())) {
            return Mono.just(ResponseEntity.status(403).body(Map.of("status", "forbidden", "message", "submitToken 无效")));
        }
        onboardingJobService.updateStatus(jobId, OnboardingJobService.JobStatus.SUBMITTED, "Agent 已提交接入信息", request.agentId(), request.provider(), request.endpoint(), request.model());
        String scope = request.scope() == null || request.scope().isBlank() ? "sandbox:invoke" : request.scope();
        registryService.register(request.agentId(), request.provider(), request.endpoint(), scope, request.apiKey(), request.model());
        onboardingJobService.updateStatus(jobId, OnboardingJobService.JobStatus.REGISTERED, "接入实例创建成功，开始联通测试", request.agentId(), request.provider(), request.endpoint(), request.model());
        auditService.record(httpRequest.getId(), "partner", "agent_register_by_job", request.agentId(), "ok");

        boolean needProbe = request.runProbe() == null || request.runProbe();
        if (!needProbe) {
            onboardingJobService.updateStatus(jobId, OnboardingJobService.JobStatus.REGISTERED, "接入实例创建成功（跳过联通测试）", request.agentId(), request.provider(), request.endpoint(), request.model());
            return Mono.just(ResponseEntity.ok(Map.of("status", "registered", "jobId", jobId)));
        }

        // 异步执行探活，不阻塞线程
        return invokeService
                .invokeAgent(
                        new AgentRegistryService.RegisteredAgent(
                                request.agentId(),
                                request.provider(),
                                request.endpoint(),
                                scope,
                                request.apiKey(),
                                request.model(),
                                java.time.Instant.now()
                        ),
                        "sess_probe_" + jobId,
                        1,
                        "请回复：联通成功"
                )
                .timeout(Duration.ofSeconds(20))
                .flatMap(probeText -> {
                    String msg = (probeText == null || probeText.isBlank()) ? "联通成功（返回为空）" : "联通成功：" + trimForUi(probeText, 120);
                    onboardingJobService.updateStatus(jobId, OnboardingJobService.JobStatus.VERIFIED, msg, request.agentId(), request.provider(), request.endpoint(), request.model());
                    Map<String, Object> responseBody = Map.of("status", (Object)"verified", "jobId", jobId, "message", msg);
                    return Mono.just(ResponseEntity.ok(responseBody));
                })
                .onErrorResume(e -> {
                    String rawErr = e.getMessage() == null ? e.toString() : e.getMessage();
                    String hint = "";
                    if (rawErr.contains("HTTP 401") || rawErr.toLowerCase().contains("unauthorized")) {
                        boolean apiKeyMissing = request.apiKey() == null || request.apiKey().isBlank();
                        hint = apiKeyMissing
                                ? "（提示：你的 endpoint 要求 Bearer 鉴权，但 submit 时 apiKey 为空。请在 submit 时填入你 endpoint 的 API Key。）"
                                : "（提示：apiKey 已传但被拒绝，请确认 key 是否正确、是否过期、endpoint 是否匹配。）";
                    } else if (rawErr.contains("HTTP 408") || rawErr.toLowerCase().contains("timeout")) {
                        hint = "（提示：endpoint 没有及时响应，请确认你的服务在线且可达。）";
                    } else if (rawErr.contains("HTTP 503")) {
                        hint = "（提示：endpoint 不可用，请检查隧道/服务是否运行。）";
                    }
                    String msg = "联通失败：" + rawErr + hint;
                    onboardingJobService.updateStatus(jobId, OnboardingJobService.JobStatus.FAILED, msg, request.agentId(), request.provider(), request.endpoint(), request.model());
                    Map<String, Object> responseBody = Map.of("status", (Object)"failed", "jobId", jobId, "message", msg);
                    return Mono.just(ResponseEntity.status(502).body(responseBody));
                });
    }

    @GetMapping("/onboarding-jobs/{jobId}/status")
    public ResponseEntity<Map<String, Object>> onboardingJobStatus(@PathVariable String jobId) {
        return onboardingJobService.find(jobId)
                .map(job -> ResponseEntity.ok(Map.<String, Object>of(
                        "jobId", job.jobId(),
                        "status", job.status().name(),
                        "message", job.message(),
                        "agentId", job.agentId() == null ? "" : job.agentId(),
                        "provider", job.provider() == null ? "" : job.provider(),
                        "endpoint", job.endpoint() == null ? "" : job.endpoint(),
                        "model", job.model() == null ? "" : job.model(),
                        "createdAt", job.createdAt().toString(),
                        "updatedAt", job.updatedAt().toString()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
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

    private static String trimForUi(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "...";
    }
}
