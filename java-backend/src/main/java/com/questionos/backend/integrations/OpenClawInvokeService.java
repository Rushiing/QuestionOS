package com.questionos.backend.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
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

    private Mono<String> invokeOpenAICompatible(AgentRegistryService.RegisteredAgent agent, String systemPrompt, String userMessage) {
        String base = agent.endpoint().endsWith("/") ? agent.endpoint().substring(0, agent.endpoint().length() - 1) : agent.endpoint();
        String url = base.endsWith("/v1/chat/completions") ? base : base + "/v1/chat/completions";
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
