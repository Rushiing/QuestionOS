package com.questionos.backend.integrations;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentRegistryService {
    public record RegisteredAgent(
            String agentId,
            String provider,
            String endpoint,
            String scope,
            String apiKey,
            String model,
            Instant registeredAt
    ) {}

    private final Map<String, RegisteredAgent> registry = new ConcurrentHashMap<>();

    public RegisteredAgent register(String agentId, String provider, String endpoint, String scope, String apiKey, String model) {
        RegisteredAgent agent = new RegisteredAgent(agentId, provider, endpoint, scope, apiKey, model, Instant.now());
        registry.put(agentId, agent);
        return agent;
    }

    public List<RegisteredAgent> all() {
        return registry.values().stream().toList();
    }

    public Optional<RegisteredAgent> find(String agentId) {
        return Optional.ofNullable(registry.get(agentId));
    }

    public Optional<String> firstAvailableAgentSummary() {
        return registry.values().stream().findFirst()
                .map(a -> "第三方Agent已接入: " + a.agentId() + " (" + a.provider() + ")");
    }

    public Optional<RegisteredAgent> firstAvailableAgent() {
        return registry.values().stream().findFirst();
    }
}
