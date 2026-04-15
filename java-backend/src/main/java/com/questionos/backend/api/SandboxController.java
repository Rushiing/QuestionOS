package com.questionos.backend.api;

import com.questionos.backend.api.dto.SandboxDtos;
import com.questionos.backend.domain.ConversationSession;
import com.questionos.backend.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

@Validated
@RestController
@RequestMapping("/api/v1/sandbox/sessions")
@CrossOrigin(
        originPatterns = {"https://*.up.railway.app", "http://localhost:*", "http://127.0.0.1:*"},
        allowedHeaders = {"Authorization", "Content-Type", "X-API-Version", "Idempotency-Key", "Last-Event-ID", "Accept", "Origin"},
        methods = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS},
        allowCredentials = "false"
)
public class SandboxController {
    private final SessionService sessionService;

    public SandboxController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    private String currentUserId(org.springframework.web.server.ServerWebExchange exchange) {
        Object uid = exchange.getAttribute("authUserId");
        return uid == null ? "" : String.valueOf(uid);
    }

    @PostMapping
    public ResponseEntity<SandboxDtos.CreateSessionResponse> create(
            org.springframework.web.server.ServerWebExchange exchange,
            @Valid @RequestBody SandboxDtos.CreateSessionRequest request
    ) {
        ConversationSession session = sessionService.createSession(currentUserId(exchange), request.mode(), request.question());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new SandboxDtos.CreateSessionResponse(session.getSessionId(), session.getStatus().name().toLowerCase(), session.getCreatedAt())
        );
    }

    @GetMapping
    public ResponseEntity<SandboxDtos.SessionListResponse> list(org.springframework.web.server.ServerWebExchange exchange) {
        var items = sessionService.listSessions(currentUserId(exchange)).stream()
                .map(s -> new SandboxDtos.SessionListItem(
                        s.getSessionId(),
                        s.getMode(),
                        s.getStatus(),
                        s.getMessageCount(),
                        s.getCreatedAt(),
                        s.getLastActivityAt(),
                        s.getDisplayTitle()
                ))
                .toList();
        return ResponseEntity.ok(new SandboxDtos.SessionListResponse(items));
    }

    /**
     * 在 boundedElastic 上执行：SessionService 内会对 LLM 使用 {@code Mono#block}，
     * 不可在 WebFlux 的 reactor-http 事件循环线程上直接阻塞。
     */
    @PostMapping("/{sessionId}/messages")
    public Mono<ResponseEntity<SandboxDtos.SendMessageResponse>> send(
            org.springframework.web.server.ServerWebExchange exchange,
            @PathVariable String sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SandboxDtos.SendMessageRequest request
    ) {
        String uid = currentUserId(exchange);
        return Mono.fromCallable(() -> sessionService.acceptUserMessage(uid, sessionId, request.content(), idempotencyKey))
                .subscribeOn(Schedulers.boundedElastic())
                .map(maybeMessageId -> maybeMessageId
                        .map(messageId -> ResponseEntity.ok(
                                new SandboxDtos.SendMessageResponse(messageId, "accepted", idempotencyKey)))
                        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build()));
    }

    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            org.springframework.web.server.ServerWebExchange exchange,
            @PathVariable String sessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        Long parsed = null;
        if (lastEventId != null && !lastEventId.startsWith("hb-") && !lastEventId.isBlank()) {
            try {
                parsed = Long.parseLong(lastEventId);
            } catch (NumberFormatException ignored) {
                parsed = null;
            }
        }
        return sessionService.stream(currentUserId(exchange), sessionId, parsed);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<SandboxDtos.SessionStatusResponse> get(
            org.springframework.web.server.ServerWebExchange exchange,
            @PathVariable String sessionId
    ) {
        return sessionService.getSession(currentUserId(exchange), sessionId)
                .map(session -> ResponseEntity.ok(new SandboxDtos.SessionStatusResponse(
                        session.getSessionId(),
                        session.getMode(),
                        session.getStatus(),
                        session.getMessageCount(),
                        session.getCreatedAt(),
                        session.getExpiresAt(),
                        session.getLastActivityAt()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<SandboxDtos.SessionMessagesResponse> messages(
            org.springframework.web.server.ServerWebExchange exchange,
            @PathVariable String sessionId
    ) {
        if (sessionService.getSession(currentUserId(exchange), sessionId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var items = sessionService.listMessages(sessionId).stream()
                .map(m -> new SandboxDtos.SessionMessageItem(
                        m.messageId(),
                        m.role(),
                        m.content(),
                        m.turnId(),
                        m.createdAt(),
                        m.agentSpeakerId()
                ))
                .toList();
        return ResponseEntity.ok(new SandboxDtos.SessionMessagesResponse(sessionId, items));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<SandboxDtos.DeleteSessionResponse> delete(
            org.springframework.web.server.ServerWebExchange exchange,
            @PathVariable String sessionId
    ) {
        boolean deleted = sessionService.deleteSession(currentUserId(exchange), sessionId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new SandboxDtos.DeleteSessionResponse("deleted"));
    }
}
