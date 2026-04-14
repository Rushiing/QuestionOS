package com.questionos.backend.persistence;

import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.ConversationSession;
import com.questionos.backend.domain.MessageRole;
import com.questionos.backend.domain.SessionMode;
import com.questionos.backend.domain.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 持久化；profile=postgres 且 {@code questionos.session.persistence.backend=jdbc}。
 * 不使用 {@code @ConditionalOnBean(DataSource)}：在 WebFlux 启动链里该条件可能早于 DataSource 注册，导致本 Bean 被错误跳过且无其它 SessionSnapshotPersistence 实现。
 */
@Component
@ConditionalOnProperty(name = "questionos.session.persistence.backend", havingValue = "jdbc")
public class JdbcSessionSnapshotPersistence implements SessionSnapshotPersistence {
    private static final Logger log = LoggerFactory.getLogger(JdbcSessionSnapshotPersistence.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcSessionSnapshotPersistence(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void save(ConversationSession session, List<ConversationMessage> messages) {
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update(
                        """
                                INSERT INTO qos_conversation_session (
                                  session_id, owner_user_id, mode, status,
                                  created_at, last_activity_at, expires_at, display_title,
                                  turn_seq, sandbox_speaker_round, sandbox_deliberation_scene
                                ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                                ON CONFLICT (session_id) DO UPDATE SET
                                  owner_user_id = EXCLUDED.owner_user_id,
                                  mode = EXCLUDED.mode,
                                  status = EXCLUDED.status,
                                  last_activity_at = EXCLUDED.last_activity_at,
                                  expires_at = EXCLUDED.expires_at,
                                  display_title = EXCLUDED.display_title,
                                  turn_seq = EXCLUDED.turn_seq,
                                  sandbox_speaker_round = EXCLUDED.sandbox_speaker_round,
                                  sandbox_deliberation_scene = EXCLUDED.sandbox_deliberation_scene
                                """,
                        session.getSessionId(),
                        session.getOwnerUserId(),
                        session.getMode().name(),
                        session.getStatus().name(),
                        Timestamp.from(session.getCreatedAt()),
                        Timestamp.from(session.getLastActivityAt()),
                        Timestamp.from(session.getExpiresAt()),
                        session.getDisplayTitle(),
                        session.currentTurnSeq(),
                        session.currentSandboxSpeakerRound(),
                        session.getSandboxDeliberationScene());
                jdbc.update("DELETE FROM qos_conversation_message WHERE session_id = ?", session.getSessionId());
                for (ConversationMessage m : messages) {
                    jdbc.update(
                            """
                                    INSERT INTO qos_conversation_message (
                                      message_id, session_id, turn_id, role, content, created_at, agent_speaker_id
                                    ) VALUES (?,?,?,?,?,?,?)
                                    """,
                            m.messageId(),
                            m.sessionId(),
                            m.turnId(),
                            m.role().name(),
                            m.content(),
                            Timestamp.from(m.createdAt()),
                            m.agentSpeakerId());
                }
            });
        } catch (Exception e) {
            log.warn("failed to persist session {} to postgres", session.getSessionId(), e);
        }
    }

    @Override
    public void delete(String sessionId) {
        try {
            jdbc.update("DELETE FROM qos_conversation_session WHERE session_id = ?", sessionId);
        } catch (Exception e) {
            log.warn("failed to delete persisted session {}", sessionId, e);
        }
    }

    @Override
    public List<LoadedSession> loadAll() {
        List<LoadedSession> out = new ArrayList<>();
        try {
            Map<String, List<ConversationMessage>> bySession = new LinkedHashMap<>();
            RowCallbackHandler collectMessages = rs -> {
                ConversationMessage m = mapMessage(rs);
                bySession.computeIfAbsent(m.sessionId(), k -> new ArrayList<>()).add(m);
            };
            jdbc.query(
                    "SELECT * FROM qos_conversation_message ORDER BY session_id, created_at ASC, message_id ASC",
                    collectMessages);

            List<ConversationSession> sessionRows = jdbc.query(
                    "SELECT * FROM qos_conversation_session ORDER BY created_at ASC",
                    (rs, rowNum) -> mapSessionRow(rs, bySession.getOrDefault(rs.getString("session_id"), List.of()).size()));

            for (ConversationSession s : sessionRows) {
                out.add(new LoadedSession(s, bySession.getOrDefault(s.getSessionId(), List.of())));
            }
        } catch (Exception e) {
            log.warn("failed to load sessions from postgres", e);
        }
        return out;
    }

    private static ConversationSession mapSessionRow(ResultSet rs, int messageCount) throws SQLException {
        String sid = rs.getString("session_id");
        String owner = rs.getString("owner_user_id");
        SessionMode mode = SessionMode.valueOf(rs.getString("mode"));
        SessionStatus status = SessionStatus.valueOf(rs.getString("status"));
        Instant created = rs.getObject("created_at", Instant.class);
        Instant last = rs.getObject("last_activity_at", Instant.class);
        Instant exp = rs.getObject("expires_at", Instant.class);
        String title = rs.getString("display_title");
        long turnSeq = rs.getLong("turn_seq");
        int sandbox = rs.getInt("sandbox_speaker_round");
        String deliberationScene = rs.getString("sandbox_deliberation_scene");
        return ConversationSession.restore(
                sid,
                owner != null ? owner : "",
                mode,
                status,
                created != null ? created : Instant.now(),
                last != null ? last : Instant.now(),
                exp != null ? exp : Instant.now(),
                title,
                turnSeq,
                sandbox,
                messageCount,
                deliberationScene);
    }

    private static ConversationMessage mapMessage(ResultSet rs) throws SQLException {
        MessageRole role = MessageRole.valueOf(rs.getString("role"));
        Instant created = rs.getObject("created_at", Instant.class);
        return new ConversationMessage(
                rs.getString("message_id"),
                rs.getString("session_id"),
                rs.getLong("turn_id"),
                role,
                rs.getString("content"),
                created != null ? created : Instant.now(),
                rs.getString("agent_speaker_id"));
    }
}
