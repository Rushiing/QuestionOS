package com.questionos.backend.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenClawInvokeService {
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // 当未接入任何三方 agents 时，内置四角色需要一个“真实 LLM”来继续沙盘推演。
    @Value("${questionos.llm.endpoint:}")
    private String defaultLlmEndpoint;
    @Value("${questionos.llm.apiKey:}")
    private String defaultLlmApiKey;
    @Value("${questionos.llm.model:}")
    private String defaultLlmModel;

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
        if (defaultLlmEndpoint == null || defaultLlmEndpoint.isBlank()) {
            return Mono.error(new IllegalStateException(
                    "未配置 questionos.llm.endpoint（QUESTIONOS_LLM_ENDPOINT 为空）。请先设置后重试。"
            ));
        }
        return invokeOpenAICompatibleRaw(defaultLlmEndpoint, defaultLlmApiKey, defaultLlmModel, systemPrompt, userMessage);
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

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", msgList);

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (!apiKey.isBlank()) {
                        h.setBearerAuth(apiKey);
                    }
                })
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(25))
                .map(this::extractContent)
                .map(text -> text == null || text.isBlank() ? "OpenClaw 返回为空。" : text);
    }

    private Mono<String> invokeOpenAICompatibleRaw(
            String endpoint,
            String apiKey,
            String model,
            String systemPrompt,
            String userMessage
    ) {
        String url = normalizeChatCompletionsUrl(endpoint);
        String useModel = (model == null || model.isBlank()) ? "custom-dogfooding/pitaya-03-20" : model;
        String useApiKey = apiKey == null ? "" : apiKey.trim();

        List<Map<String, String>> msgList = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgList.add(Map.of("role", "system", "content", systemPrompt));
        }
        msgList.add(Map.of("role", "user", "content", userMessage == null ? "" : userMessage));

        Map<String, Object> body = new HashMap<>();
        body.put("model", useModel);
        body.put("messages", msgList);

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (!useApiKey.isBlank()) {
                        h.setBearerAuth(useApiKey);
                    }
                })
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(25))
                .map(this::extractContent)
                .map(text -> text == null || text.isBlank() ? "LLM 返回为空。" : text);
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
