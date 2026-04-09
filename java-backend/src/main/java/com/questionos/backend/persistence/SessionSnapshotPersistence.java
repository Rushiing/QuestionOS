package com.questionos.backend.persistence;

import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.ConversationSession;

import java.util.List;

/**
 * 会话快照持久化（文件或 JDBC），供 {@link com.questionos.backend.service.SessionService} 在重启后恢复历史。
 */
public interface SessionSnapshotPersistence {

    boolean isEnabled();

    void save(ConversationSession session, List<ConversationMessage> messages);

    void delete(String sessionId);

    List<LoadedSession> loadAll();

    record LoadedSession(ConversationSession session, List<ConversationMessage> messages) {}
}
