package com.questionos.backend.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.ConversationSession;
import com.questionos.backend.domain.MessageRole;
import com.questionos.backend.domain.SessionMode;
import com.questionos.backend.domain.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 将会话与消息以 JSON 写入磁盘，进程重启后按 ownerUserId 恢复列表与继续对话。
 * 生产环境优先使用 {@code postgres} 配置 + JDBC；本地默认使用本实现。
 */
@Component
@ConditionalOnProperty(name = "questionos.session.persistence.backend", havingValue = "file", matchIfMissing = true)
public class JsonFileSessionPersistence implements SessionSnapshotPersistence {
    private static final Logger log = LoggerFactory.getLogger(JsonFileSessionPersistence.class);

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Path directory;

    public JsonFileSessionPersistence(
            ObjectMapper objectMapper,
            @Value("${questionos.session.persistence.enabled:true}") boolean enabled,
            @Value("${questionos.session.persistence.directory:}") String directoryRaw
    ) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        String raw = directoryRaw == null ? "" : directoryRaw.trim();
        if (raw.isEmpty()) {
            this.directory = Path.of(System.getProperty("user.dir", "."), "data", "questionos-sessions");
        } else {
            this.directory = Path.of(raw);
        }
    }

    @PostConstruct
    void initDir() {
        if (!enabled) {
            log.info("session persistence disabled (questionos.session.persistence.enabled=false)");
            return;
        }
        try {
            Files.createDirectories(directory);
            log.info("session persistence directory={}", directory.toAbsolutePath());
        } catch (IOException e) {
            log.error("failed to create session persistence directory {}", directory, e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void save(ConversationSession session, List<ConversationMessage> messageList) {
        if (!enabled) {
            return;
        }
        try {
            Files.createDirectories(directory);
            PersistedSessionData data = new PersistedSessionData();
            data.setSessionId(session.getSessionId());
            data.setOwnerUserId(session.getOwnerUserId());
            data.setMode(session.getMode().name());
            data.setStatus(session.getStatus().name());
            data.setCreatedAt(session.getCreatedAt());
            data.setLastActivityAt(session.getLastActivityAt());
            data.setExpiresAt(session.getExpiresAt());
            data.setDisplayTitle(session.getDisplayTitle());
            data.setTurnSeq(session.currentTurnSeq());
            data.setSandboxSpeakerRound(session.currentSandboxSpeakerRound());
            data.setSandboxDeliberationScene(session.getSandboxDeliberationScene());
            List<PersistedMessageData> out = new ArrayList<>();
            for (ConversationMessage m : messageList) {
                PersistedMessageData pm = new PersistedMessageData();
                pm.setMessageId(m.messageId());
                pm.setSessionId(m.sessionId());
                pm.setTurnId(m.turnId());
                pm.setRole(m.role().name());
                pm.setContent(m.content());
                pm.setCreatedAt(m.createdAt());
                pm.setAgentSpeakerId(m.agentSpeakerId());
                out.add(pm);
            }
            data.setMessages(out);
            Path target = fileFor(session.getSessionId());
            Path tmp = Path.of(target.toString() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("failed to persist session {}", session.getSessionId(), e);
        }
    }

    public void delete(String sessionId) {
        if (!enabled) {
            return;
        }
        try {
            Files.deleteIfExists(fileFor(sessionId));
        } catch (IOException e) {
            log.warn("failed to delete persisted session {}", sessionId, e);
        }
    }

    public List<SessionSnapshotPersistence.LoadedSession> loadAll() {
        List<SessionSnapshotPersistence.LoadedSession> result = new ArrayList<>();
        if (!enabled || !Files.isDirectory(directory)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(path -> {
                        try {
                            SessionSnapshotPersistence.LoadedSession one = loadFile(path);
                            if (one != null) {
                                result.add(one);
                            }
                        } catch (Exception e) {
                            log.warn("skip corrupt session file {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("failed to list session persistence directory {}", directory, e);
        }
        return result;
    }

    private SessionSnapshotPersistence.LoadedSession loadFile(Path path) throws IOException {
        PersistedSessionData data = objectMapper.readValue(path.toFile(), PersistedSessionData.class);
        if (data.getSessionId() == null || data.getSessionId().isBlank()) {
            return null;
        }
        String sid = data.getSessionId();
        SessionMode mode = parseEnum(SessionMode.class, data.getMode(), SessionMode.SANDBOX);
        SessionStatus status = parseEnum(SessionStatus.class, data.getStatus(), SessionStatus.ACTIVE);
        List<ConversationMessage> msgs = new ArrayList<>();
        if (data.getMessages() != null) {
            for (PersistedMessageData pm : data.getMessages()) {
                MessageRole role = parseEnum(MessageRole.class, pm.getRole(), MessageRole.USER);
                msgs.add(new ConversationMessage(
                        pm.getMessageId() != null ? pm.getMessageId() : "msg_restored",
                        pm.getSessionId() != null ? pm.getSessionId() : sid,
                        pm.getTurnId(),
                        role,
                        pm.getContent() != null ? pm.getContent() : "",
                        pm.getCreatedAt() != null ? pm.getCreatedAt() : java.time.Instant.now(),
                        pm.getAgentSpeakerId()
                ));
            }
        }
        ConversationSession session = ConversationSession.restore(
                sid,
                data.getOwnerUserId() != null ? data.getOwnerUserId() : "",
                mode,
                status,
                data.getCreatedAt() != null ? data.getCreatedAt() : java.time.Instant.now(),
                data.getLastActivityAt() != null ? data.getLastActivityAt() : java.time.Instant.now(),
                data.getExpiresAt() != null ? data.getExpiresAt() : java.time.Instant.now(),
                data.getDisplayTitle(),
                data.getTurnSeq(),
                data.getSandboxSpeakerRound(),
                msgs.size(),
                data.getSandboxDeliberationScene()
        );
        return new SessionSnapshotPersistence.LoadedSession(session, msgs);
    }

    private Path fileFor(String sessionId) {
        String safe = sessionId.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return directory.resolve(safe + ".json");
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

}
