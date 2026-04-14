package com.questionos.backend.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class OpenClawInvokeService {
    private static final Logger log = LoggerFactory.getLogger(OpenClawInvokeService.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // 当未接入任何三方 agents 时，内置四角色需要一个“真实 LLM”来继续沙盘推演。
    @Value("${questionos.llm.endpoint:}")
    private String defaultLlmEndpoint;
    @Value("${questionos.llm.apiKey:}")
    private String defaultLlmApiKey;
    @Value("${questionos.llm.model:}")
    private String defaultLlmModel;
    @Value("${questionos.llm.timeoutSeconds:240}")
    private int defaultLlmTimeoutSeconds;
    @Value("${questionos.llm.maxTokens:4096}")
    private int defaultLlmMaxTokens;
    /** 为 DashScope 兼容请求附加 extra_body.enable_thinking；默认 false */
    @Value("${questionos.llm.extraBodyEnableThinking:false}")
    private boolean extraBodyEnableThinking;
    @Value("${questionos.llm.streamChatCompletions:true}")
    private boolean streamChatCompletionsDefault;

    public OpenClawInvokeService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public Mono<String> invokeAgent(AgentRegistryService.RegisteredAgent agent, String sessionId, long turnId, String input) {
        if ("openclaw".equalsIgnoreCase(agent.provider())) {
            return invokeOpenAICompatible(agent, null, input);
        }
        Map<String, Object> payload = Map.of(
                "sessionId", sessionId,
                "turnId", turnId,
                "input", input,
                "mode", "sandbox",
                "timestamp", Instant.now().toString()
        );

        return webClient.post()
                .uri(agent.endpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(8))
                .map(this::extractContent)
                .map(text -> text == null || text.isBlank() ? "第三方Agent返回为空。" : text);
    }

    /**
     * OpenClaw / OpenAI 兼容：可选 system + user。
     */
    public Mono<String> invokeOpenClaw(AgentRegistryService.RegisteredAgent agent, String systemPrompt, String userMessage) {
        if (!"openclaw".equalsIgnoreCase(agent.provider())) {
            return Mono.error(new IllegalStateException("仅支持 provider=OpenClaw 的实例"));
        }
        return invokeOpenAICompatible(agent, systemPrompt, userMessage);
    }

    /**
     * 本地真实调用 LLM（当 registry 为空：仍要让内置四角色沙盘推演可跑）。
     *
     * endpoint 支持：
     * - OpenAI： https://api.openai.com
     * - OpenAI-compatible： https://xxx 或直接带 /v1/chat/completions
     */
    public Mono<String> invokeDefaultLlm(String systemPrompt, String userMessage) {
        return invokeDefaultLlm(systemPrompt, userMessage, "llm");
    }

    /**
     * @param stage 日志关联用短标签，如 calibration|sess_xxx|t3、session-title、sandbox:auditor
     */
    public Mono<String> invokeDefaultLlm(String systemPrompt, String userMessage, String stage) {
        if (defaultLlmEndpoint == null || defaultLlmEndpoint.isBlank()) {
            return Mono.error(new IllegalStateException(
                    "未配置 questionos.llm.endpoint（QUESTIONOS_LLM_ENDPOINT 为空）。请先设置后重试。"
            ));
        }
        if (streamChatCompletionsDefault) {
            return invokeDefaultLlmStreamingCollect(
                    defaultLlmEndpoint, defaultLlmApiKey, defaultLlmModel, systemPrompt, userMessage, stage, -1, -1);
        }
        return invokeOpenAICompatibleRaw(
                defaultLlmEndpoint, defaultLlmApiKey, defaultLlmModel, systemPrompt, userMessage, stage, -1, -1);
    }

    /**
     * 非流式、小 max_tokens、短超时；用于沙盘场景分类等轻量调用。
     */
    public Mono<String> invokeDefaultLlmCompact(
            String systemPrompt,
            String userMessage,
            String stage,
            int maxTokens,
            int timeoutSeconds
    ) {
        if (defaultLlmEndpoint == null || defaultLlmEndpoint.isBlank()) {
            return Mono.error(new IllegalStateException(
                    "未配置 questionos.llm.endpoint（QUESTIONOS_LLM_ENDPOINT 为空）。请先设置后重试。"
            ));
        }
        return invokeOpenAICompatibleRaw(
                defaultLlmEndpoint,
                defaultLlmApiKey,
                defaultLlmModel,
                systemPrompt,
                userMessage,
                stage,
                timeoutSeconds,
                maxTokens);
    }

    /**
     * OpenAI 兼容流式（SSE）：拼接 {@code choices[0].delta.content}，完成后与整包调用结果一致，便于首包更早到达。
     */
    public Mono<String> invokeDefaultLlmStreamingCollect(
            String endpoint,
            String apiKey,
            String model,
            String systemPrompt,
            String userMessage,
            String stage,
            int timeoutSecondsOverride,
            int maxTokensOverride
    ) {
        String url = normalizeChatCompletionsUrl(endpoint);
        String useModel = (model == null || model.isBlank()) ? "custom-dogfooding/pitaya-03-20" : model;
        String useApiKey = apiKey == null ? "" : apiKey.trim();
        List<Map<String, String>> msgList = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgList.add(Map.of("role", "system", "content", systemPrompt));
        }
        msgList.add(Map.of("role", "user", "content", userMessage == null ? "" : userMessage));
        Map<String, Object> body = newOpenAiChatBody(useModel, msgList, endpoint, true, maxTokensOverride);
        int effSec = effectiveTimeoutSeconds(timeoutSecondsOverride);
        Duration timeout = Duration.ofSeconds(effSec);
        return Mono.fromCallable(() -> {
                    StringBuilder acc = new StringBuilder();
                    executeChatCompletionsStreamConsuming(url, body, useApiKey, useModel, stage, timeout, acc::append);
                    return acc.toString();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(timeout.plusSeconds(5));
    }

    /**
     * 流式：每收到一段 {@code delta.content} 即向下游发射；用于校准真·流式 UI（与 {@link #invokeDefaultLlmStreamingCollect} 同一条 HTTP SSE）。
     */
    public Flux<String> invokeDefaultLlmStreamingDeltas(
            String systemPrompt,
            String userMessage,
            String stage
    ) {
        if (defaultLlmEndpoint == null || defaultLlmEndpoint.isBlank()) {
            return Flux.error(new IllegalStateException(
                    "未配置 questionos.llm.endpoint（QUESTIONOS_LLM_ENDPOINT 为空）。请先设置后重试。"
            ));
        }
        String url = normalizeChatCompletionsUrl(defaultLlmEndpoint);
        String useModel = (defaultLlmModel == null || defaultLlmModel.isBlank())
                ? "custom-dogfooding/pitaya-03-20"
                : defaultLlmModel;
        String useApiKey = defaultLlmApiKey == null ? "" : defaultLlmApiKey.trim();
        List<Map<String, String>> msgList = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgList.add(Map.of("role", "system", "content", systemPrompt));
        }
        msgList.add(Map.of("role", "user", "content", userMessage == null ? "" : userMessage));
        Map<String, Object> body = newOpenAiChatBody(useModel, msgList, defaultLlmEndpoint, true, -1);
        int effSec = effectiveTimeoutSeconds(-1);
        Duration timeout = Duration.ofSeconds(effSec);
        return Flux.<String>create(sink -> {
                    try {
                        executeChatCompletionsStreamConsuming(
                                url, body, useApiKey, useModel, stage, timeout, piece -> {
                                    if (piece != null && !piece.isEmpty()) {
                                        sink.next(piece);
                                    }
                                });
                        sink.complete();
                    } catch (Exception e) {
                        sink.error(e);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> invokeOpenAICompatible(AgentRegistryService.RegisteredAgent agent, String systemPrompt, String userMessage) {
        String url = normalizeChatCompletionsUrl(agent.endpoint());
        String model = (agent.model() == null || agent.model().isBlank()) ? "custom-dogfooding/pitaya-03-20" : agent.model();
        String apiKey = agent.apiKey() == null ? "" : agent.apiKey().trim();

        List<Map<String, String>> msgList = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgList.add(Map.of("role", "system", "content", systemPrompt));
        }
        msgList.add(Map.of("role", "user", "content", userMessage == null ? "" : userMessage));

        Map<String, Object> body = newOpenAiChatBody(model, msgList, agent.endpoint(), false, -1);

        Duration agentTimeout = Duration.ofSeconds(effectiveTimeoutSeconds(-1));
        return executeChatCompletions(
                url,
                body,
                apiKey,
                model,
                "sandbox:" + agent.agentId(),
                agentTimeout);
    }

    /**
     * @param timeoutSecondsOverride &gt;= 0 时使用该秒数作为超时；&lt; 0 时使用 {@link #defaultLlmTimeoutSeconds}（且至少 25s）
     * @param maxTokensOverride &gt; 0 时覆盖 max_tokens；&lt;= 0 时使用 {@link #defaultLlmMaxTokens}
     */
    private Mono<String> invokeOpenAICompatibleRaw(
            String endpoint,
            String apiKey,
            String model,
            String systemPrompt,
            String userMessage,
            String stage,
            int timeoutSecondsOverride,
            int maxTokensOverride
    ) {
        String url = normalizeChatCompletionsUrl(endpoint);
        String useModel = (model == null || model.isBlank()) ? "custom-dogfooding/pitaya-03-20" : model;
        String useApiKey = apiKey == null ? "" : apiKey.trim();

        List<Map<String, String>> msgList = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgList.add(Map.of("role", "system", "content", systemPrompt));
        }
        msgList.add(Map.of("role", "user", "content", userMessage == null ? "" : userMessage));

        Map<String, Object> body = newOpenAiChatBody(useModel, msgList, endpoint, false, maxTokensOverride);

        int effSec = effectiveTimeoutSeconds(timeoutSecondsOverride);
        Duration llmTimeout = Duration.ofSeconds(effSec);
        return executeChatCompletions(url, body, useApiKey, useModel, stage, llmTimeout);
    }

    /** 显式短超时（如分类）用 override；否则主对话至少 25s。 */
    private int effectiveTimeoutSeconds(int timeoutSecondsOverride) {
        if (timeoutSecondsOverride >= 0) {
            return Math.max(5, timeoutSecondsOverride);
        }
        return Math.max(25, defaultLlmTimeoutSeconds);
    }

    /**
     * 拆耗时：ttfbMs（发起到收到 HTTP 响应头，近似「首字节」）、bodyReadMs（读响应体）、httpRoundTripMs 为二者之和；
     * 若百炼整包缓冲后再回，常见 ttfbMs≈httpRoundTripMs、bodyReadMs≈0，慢在首字节前（建连/排队/推理）。
     */
    private Mono<String> executeChatCompletions(
            String url,
            Map<String, Object> body,
            String apiKey,
            String model,
            String stage,
            Duration timeout
    ) {
        final long[] subscribeAt = new long[1];
        int sysLen = 0;
        int userLen = 0;
        Object msgs = body.get("messages");
        if (msgs instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    String role = String.valueOf(m.get("role"));
                    String c = m.get("content") == null ? "" : String.valueOf(m.get("content"));
                    if ("system".equals(role)) {
                        sysLen = c.length();
                    } else if ("user".equals(role)) {
                        userLen = c.length();
                    }
                }
            }
        }
        boolean dashExtra = body.containsKey("extra_body");
        String extraThinkingLog = "n/a";
        Object ebObj = body.get("extra_body");
        if (ebObj instanceof Map<?, ?> em) {
            Object et = em.get("enable_thinking");
            extraThinkingLog = et == null ? "null" : String.valueOf(et);
        }
        log.info(
                "LLM request start stage={} model={} timeoutSec={} systemChars={} userChars={} dashScopeExtraBody={} extraEnableThinking={} url={}",
                stage,
                model,
                timeout.getSeconds(),
                sysLen,
                userLen,
                dashExtra,
                extraThinkingLog,
                shortenUrlForLog(url));

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (apiKey != null && !apiKey.isBlank()) {
                        h.setBearerAuth(apiKey);
                    }
                })
                .bodyValue(body)
                .exchangeToMono(response -> {
                    long ttfbMs = subscribeAt[0] > 0 ? System.currentTimeMillis() - subscribeAt[0] : -1L;
                    if (response.statusCode().is4xxClientError() || response.statusCode().is5xxServerError()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(errorBody -> {
                                    String snippet = errorBody.isBlank()
                                            ? "(无响应体)"
                                            : errorBody.substring(0, Math.min(1500, errorBody.length()));
                                    return Mono.error(new IllegalStateException(
                                            "LLM HTTP " + response.statusCode().value() + ": " + snippet));
                                });
                    }
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(raw -> {
                                long totalMs = subscribeAt[0] > 0 ? System.currentTimeMillis() - subscribeAt[0] : -1L;
                                long bodyReadMs = Math.max(0L, totalMs - ttfbMs);
                                int rawLen = raw == null ? 0 : raw.length();
                                long tParse = System.currentTimeMillis();
                                String text = extractContent(raw);
                                long extractMs = System.currentTimeMillis() - tParse;
                                int outLen = text == null ? 0 : text.length();
                                log.info(
                                        "LLM request done stage={} model={} ttfbMs={} bodyReadMs={} httpRoundTripMs={} rawBodyChars={} extractJsonMs={} textChars={}",
                                        stage,
                                        model,
                                        ttfbMs,
                                        bodyReadMs,
                                        totalMs,
                                        rawLen,
                                        extractMs,
                                        outLen);
                                return text;
                            });
                })
                .timeout(timeout)
                .doOnSubscribe(s -> subscribeAt[0] = System.currentTimeMillis())
                .doOnError(e -> {
                    long after = subscribeAt[0] > 0 ? System.currentTimeMillis() - subscribeAt[0] : -1L;
                    log.warn("LLM request failed stage={} model={} afterMs={} err={}", stage, model, after, e.toString());
                })
                .map(text -> text == null || text.isBlank() ? "LLM 返回为空。" : text);
    }

    /**
     * 阻塞读取 SSE；每段非空 content 交给 {@code onDelta}，结束时打耗时日志。
     */
    private void executeChatCompletionsStreamConsuming(
            String url,
            Map<String, Object> body,
            String apiKey,
            String model,
            String stage,
            Duration timeout,
            Consumer<String> onDelta
    ) throws Exception {
        long start = System.currentTimeMillis();
        long firstDeltaMs = -1L;
        int deltaChunks = 0;
        int accChars = 0;
        String jsonBody = objectMapper.writeValueAsString(body);

        boolean dashExtra = body.containsKey("extra_body");
        String extraThinkingLog = "n/a";
        Object ebObj = body.get("extra_body");
        if (ebObj instanceof Map<?, ?> em) {
            Object et = em.get("enable_thinking");
            extraThinkingLog = et == null ? "null" : String.valueOf(et);
        }
        log.info(
                "LLM stream request start stage={} model={} timeoutSec={} dashScopeExtraBody={} extraEnableThinking={} stream=true url={}",
                stage,
                model,
                timeout.getSeconds(),
                dashExtra,
                extraThinkingLog,
                shortenUrlForLog(url));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout.plusSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) {
            rb.header("Authorization", "Bearer " + apiKey.trim());
        }
        HttpResponse<InputStream> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            String err = readStreamAsString(resp.body());
            throw new IllegalStateException(
                    "LLM HTTP " + resp.statusCode() + ": "
                            + (err.isBlank() ? "(无响应体)" : err.substring(0, Math.min(1500, err.length()))));
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode root = objectMapper.readTree(data);
                if (root.has("error")) {
                    JsonNode errNode = root.get("error");
                    String msg = errNode.hasNonNull("message") ? errNode.get("message").asText() : errNode.toString();
                    throw new IllegalStateException("LLM API 错误: " + msg);
                }
                String piece = extractStreamDeltaContent(root);
                if (piece != null && !piece.isEmpty()) {
                    if (firstDeltaMs < 0L) {
                        firstDeltaMs = System.currentTimeMillis() - start;
                    }
                    onDelta.accept(piece);
                    accChars += piece.length();
                    deltaChunks++;
                }
            }
        }
        long totalMs = System.currentTimeMillis() - start;
        log.info(
                "LLM stream request done stage={} model={} firstDeltaMs={} deltaChunks={} streamTotalMs={} accChars={}",
                stage,
                model,
                firstDeltaMs,
                deltaChunks,
                totalMs,
                accChars);
        if (deltaChunks == 0) {
            throw new IllegalStateException("LLM 流式返回为空");
        }
    }

    private static String readStreamAsString(InputStream in) throws java.io.IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private String extractStreamDeltaContent(JsonNode root) {
        if (!root.has("choices") || !root.get("choices").isArray() || root.get("choices").isEmpty()) {
            return "";
        }
        JsonNode ch = root.get("choices").get(0);
        if (!ch.has("delta")) {
            return "";
        }
        JsonNode delta = ch.get("delta");
        if (delta.hasNonNull("content")) {
            return delta.get("content").asText();
        }
        return "";
    }

    private static String shortenUrlForLog(String url) {
        if (url == null || url.length() <= 96) {
            return url;
        }
        return url.substring(0, 96) + "…";
    }

    /**
     * 显式关闭流式：部分兼容网关默认 stream=true，会导致非流式客户端拿到 SSE 串，解析失败。
     * DashScope/百炼 GLM 等：在 extra_body 中显式 enable_thinking，避免网关默认开启思维链拖慢首包。
     */
    private Map<String, Object> newOpenAiChatBody(
            String model,
            List<Map<String, String>> msgList,
            String endpointHint,
            boolean stream,
            int maxTokensOverride
    ) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", msgList);
        body.put("stream", stream);
        int mt = maxTokensOverride > 0 ? maxTokensOverride : defaultLlmMaxTokens;
        if (mt > 0) {
            body.put("max_tokens", mt);
        }
        if (shouldAttachDashscopeStyleExtras(endpointHint)) {
            Map<String, Object> extra = new HashMap<>();
            extra.put("enable_thinking", extraBodyEnableThinking);
            body.put("extra_body", extra);
        }
        return body;
    }

    /** 与 Python OpenAI SDK 的 extra_body 对齐；仅对百炼/DashScope 域名附加，避免向纯 OpenAI 发送未知字段 */
    private static boolean shouldAttachDashscopeStyleExtras(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }
        String e = endpoint.toLowerCase();
        return e.contains("dashscope") || e.contains("aliyuncs.com");
    }

    private String normalizeChatCompletionsUrl(String endpoint) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        if (base.endsWith("/v1/chat/completions")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }

    private String extractContent(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            // DashScope / OpenAI 兼容：部分错误以 HTTP 200 + JSON error 字段返回
            if (root.has("error")) {
                JsonNode err = root.get("error");
                String code = err.hasNonNull("code") ? err.get("code").asText() : "";
                String msg = err.hasNonNull("message") ? err.get("message").asText() : err.toString();
                String type = err.hasNonNull("type") ? err.get("type").asText() : "";
                String detail = (type.isBlank() ? "" : type + " — ") + (code.isBlank() ? "" : code + " — ") + msg;
                throw new IllegalStateException("LLM API 错误: " + detail.trim());
            }
            if (root.has("choices") && root.get("choices").isArray() && root.get("choices").size() > 0) {
                JsonNode first = root.get("choices").get(0);
                if (first.has("message") && first.get("message").hasNonNull("content")) {
                    return first.get("message").get("content").asText();
                }
                if (first.hasNonNull("text")) {
                    return first.get("text").asText();
                }
            }
            if (root.hasNonNull("content")) return root.get("content").asText();
            if (root.hasNonNull("answer")) return root.get("answer").asText();
            if (root.has("output") && root.get("output").hasNonNull("text")) return root.get("output").get("text").asText();
            if (root.has("data") && root.get("data").hasNonNull("content")) return root.get("data").get("content").asText();
            return rawBody;
        } catch (Exception ignored) {
            return rawBody;
        }
    }
}
