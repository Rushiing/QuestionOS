package com.questionos.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionos.backend.agent.AgentOrchestrator;
import com.questionos.backend.agent.AgentReplyChunk;
import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.ConversationSession;
import com.questionos.backend.domain.MessageRole;
import com.questionos.backend.domain.SessionMode;
import com.questionos.backend.domain.StreamEvent;
import com.questionos.backend.persistence.SessionSnapshotPersistence;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final AgentOrchestrator orchestrator;
    private final SessionTitleService sessionTitleService;
    private final ObjectMapper objectMapper;
    private final SessionSnapshotPersistence sessionPersistence;

    @Value("${questionos.session.titleFromLlm:false}")
    private boolean sessionTitleFromLlm;
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<ConversationMessage>> messages = new ConcurrentHashMap<>();
    private final Map<String, List<StreamEvent>> eventStore = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<StreamEvent>> sinks = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyStore = new ConcurrentHashMap<>();
    private final AtomicLong globalSeq = new AtomicLong(0);
    private static final Duration SESSION_TTL = Duration.ofHours(1);

    public SessionService(
            AgentOrchestrator orchestrator,
            SessionTitleService sessionTitleService,
            ObjectMapper objectMapper,
            SessionSnapshotPersistence sessionPersistence
    ) {
        this.orchestrator = orchestrator;
        this.sessionTitleService = sessionTitleService;
        this.objectMapper = objectMapper;
        this.sessionPersistence = sessionPersistence;
    }

    @PostConstruct
    void hydrateSessionsFromPersistence() {
        if (!sessionPersistence.isEnabled()) {
            return;
        }
        int n = 0;
        for (SessionSnapshotPersistence.LoadedSession ls : sessionPersistence.loadAll()) {
            String id = ls.session().getSessionId();
            if (sessions.containsKey(id)) {
                continue;
            }
            sessions.put(id, ls.session());
            messages.put(id, new ArrayList<>(ls.messages()));
            eventStore.put(id, new ArrayList<>());
            sinks.put(id, Sinks.many().multicast().directBestEffort());
            n++;
        }
        if (n > 0) {
            log.info("restored {} conversation session(s) from persistence store", n);
        }
    }

    private void persistSnapshot(String sessionId) {
        ConversationSession s = sessions.get(sessionId);
        if (s == null || !sessionPersistence.isEnabled()) {
            return;
        }
        List<ConversationMessage> list = messages.get(sessionId);
        sessionPersistence.save(s, list != null ? list : List.of());
    }

    public ConversationSession createSession(String ownerUserId, SessionMode mode, String question) {
        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();
        ConversationSession session = new ConversationSession(sessionId, ownerUserId, mode, now, now.plus(SESSION_TTL));
        session.setDisplayTitle(sessionTitleService.fallbackTitle(question));
        sessions.put(sessionId, session);
        messages.put(sessionId, new ArrayList<>());
        eventStore.put(sessionId, new ArrayList<>());
        sinks.put(sessionId, Sinks.many().multicast().directBestEffort());

        String q = question == null ? "" : question;
        if (sessionTitleFromLlm) {
            Schedulers.boundedElastic().schedule(() -> {
                String title = sessionTitleService.summarizeTitle(q);
                ConversationSession s = sessions.get(sessionId);
                if (s != null) {
                    s.setDisplayTitle(title);
                    persistSnapshot(sessionId);
                }
            });
        }

        // 首条用户消息由 POST /messages 写入，避免与前端「创建会话后再发送」重复
        publishEvent(sessionId, 1, "session_created", "{\"status\":\"created\"}");
        persistSnapshot(sessionId);
        return session;
    }

    public Optional<ConversationSession> getSession(String ownerUserId, String sessionId) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null || !session.getOwnerUserId().equals(ownerUserId)) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public List<ConversationSession> listSessions(String ownerUserId) {
        return sessions.values().stream()
                .filter(s -> s.getOwnerUserId().equals(ownerUserId))
                .sorted(Comparator.comparing(ConversationSession::getCreatedAt).reversed())
                .toList();
    }

    public List<ConversationMessage> listMessages(String sessionId) {
        return List.copyOf(messages.getOrDefault(sessionId, List.of()));
    }

    public Optional<String> acceptUserMessage(String ownerUserId, String sessionId, String content, String idemKey) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null || !session.getOwnerUserId().equals(ownerUserId)) {
            return Optional.empty();
        }
        String key = sessionId + ":" + idemKey;
        if (idemKey != null && idempotencyStore.containsKey(key)) {
            return Optional.of(idempotencyStore.get(key));
        }
        long turnId = session.nextTurn();
        ConversationMessage userMessage = appendMessage(session, MessageRole.USER, content, turnId, null);
        if (idemKey != null) {
            idempotencyStore.put(key, userMessage.messageId());
        }

        List<ConversationMessage> history = List.copyOf(messages.get(sessionId));
        int sandboxRound = session.getMode() == SessionMode.SANDBOX ? session.nextSandboxSpeakerRound() : 0;
        log.info(
                "session agent pipeline start sessionId={} turnId={} mode={} historySize={} userChars={}",
                sessionId,
                turnId,
                session.getMode(),
                history.size(),
                content == null ? 0 : content.length());
        AtomicReference<StringBuilder> agentReply = new AtomicReference<>(new StringBuilder());
        AtomicReference<String> activeSpeakerId = new AtomicReference<>();
        orchestrator.runPipeline(sessionId, turnId, content, session.getMode(), history, sandboxRound)
                .doOnNext(chunk -> {
                    if ("agent_start".equals(chunk.eventType())) {
                        String c = chunk.content();
                        int bar = c.indexOf('|');
                        activeSpeakerId.set(bar > 0 ? c.substring(0, bar).trim() : c.trim());
                    }
                    if ("agent_chunk".equals(chunk.eventType())) {
                        agentReply.get().append(chunk.content());
                    }
                    // agent_delta：仅实时 UI，不入库（最终仍以 agent_chunk 为准）
                    publishEvent(sessionId, turnId, chunk.eventType(), jsonPayloadForChunkContent(chunk.content()));
                })
                .doOnComplete(() -> {
                    String finalReply = agentReply.get().toString().trim();
                    if (!finalReply.isEmpty()) {
                        appendMessage(session, MessageRole.AGENT, finalReply, turnId, activeSpeakerId.get());
                    }
                    publishEvent(sessionId, turnId, "turn_done", "{\"turnId\":" + turnId + "}");
                })
                .subscribe();
        return Optional.of(userMessage.messageId());
    }

    public boolean deleteSession(String ownerUserId, String sessionId) {
        ConversationSession existing = sessions.get(sessionId);
        if (existing == null || !existing.getOwnerUserId().equals(ownerUserId)) {
            return false;
        }
        boolean existed = sessions.remove(sessionId) != null;
        messages.remove(sessionId);
        eventStore.remove(sessionId);
        sinks.remove(sessionId);
        if (existed) {
            sessionPersistence.delete(sessionId);
        }
        return existed;
    }

    public Flux<ServerSentEvent<String>> stream(String ownerUserId, String sessionId, Long lastEventId) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null || !session.getOwnerUserId().equals(ownerUserId)) {
            return Flux.empty();
        }
        Long effectiveLastEventId = lastEventId;
        long currentSeq = globalSeq.get();
        // Backend restart may reset seq to small numbers. If client sends a stale larger cursor,
        // fall back to full replay instead of returning an empty stream.
        if (effectiveLastEventId != null && effectiveLastEventId > currentSeq) {
            effectiveLastEventId = null;
        }
        final Long cursor = effectiveLastEventId;
        List<StreamEvent> replay = eventStore.getOrDefault(sessionId, List.of()).stream()
                .filter(e -> cursor == null || e.seq() > cursor)
                .toList();
        Flux<ServerSentEvent<String>> replayFlux = Flux.fromIterable(replay).map(this::toSse);
        Sinks.Many<StreamEvent> sink = sinks.get(sessionId);
        Flux<ServerSentEvent<String>> liveFlux = sink == null
                ? Flux.empty()
                : sink.asFlux().map(this::toSse);
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(20))
                .map(i -> ServerSentEvent.<String>builder()
                        .event("heartbeat")
                        .id("hb-" + i)
                        .data("{\"type\":\"heartbeat\"}")
                        .build());
        return replayFlux.concatWith(liveFlux).mergeWith(heartbeat);
    }

    public Map<String, Object> capabilities() {
        return orchestrator.capabilities();
    }

    private ConversationMessage appendMessage(ConversationSession session, MessageRole role, String content, long turnId, String agentSpeakerId) {
        Instant now = Instant.now();
        session.markMessage(now, now.plus(SESSION_TTL));
        String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ConversationMessage message = new ConversationMessage(messageId, session.getSessionId(), turnId, role, content, now, agentSpeakerId);
        messages.get(session.getSessionId()).add(message);
        persistSnapshot(session.getSessionId());
        return message;
    }

    private void publishEvent(String sessionId, long turnId, String eventType, String payload) {
        StreamEvent event = new StreamEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                sessionId,
                globalSeq.incrementAndGet(),
                turnId,
                eventType,
                payload,
                Instant.now()
        );
        eventStore.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(event);
        Sinks.Many<StreamEvent> sink = sinks.get(sessionId);
        if (sink != null) {
            sink.tryEmitNext(event);
        }
    }

    private ServerSentEvent<String> toSse(StreamEvent event) {
        return ServerSentEvent.<String>builder()
                .id(String.valueOf(event.seq()))
                .event(event.eventType())
                .data("{\"eventId\":\"" + event.eventId() + "\",\"seq\":" + event.seq() + ",\"turnId\":" + event.turnId() + ",\"payload\":" + event.payload() + "}")
                .build();
    }

    private String jsonPayloadForChunkContent(String content) {
        try {
            return objectMapper.writeValueAsString(Map.of("content", content == null ? "" : content));
        } catch (JsonProcessingException e) {
            return "{\"content\":\"\"}";
        }
    }
}
