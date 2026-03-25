package com.questionos.backend.governance;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class AuditService {
    public record AuditEntry(Instant at, String requestId, String actor, String action, String target, String result) {}

    private final List<AuditEntry> entries = new CopyOnWriteArrayList<>();

    public void record(String requestId, String actor, String action, String target, String result) {
        entries.add(new AuditEntry(Instant.now(), requestId, actor, action, target, result));
    }

    public List<AuditEntry> latest(int limit) {
        int from = Math.max(0, entries.size() - limit);
        return entries.subList(from, entries.size());
    }
}
