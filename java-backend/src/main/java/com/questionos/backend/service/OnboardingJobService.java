package com.questionos.backend.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OnboardingJobService {
    public enum JobStatus {
        DRAFT,
        SUBMITTED,
        REGISTERED,
        VERIFIED,
        FAILED
    }

    public record OnboardingJob(
            String jobId,
            String submitToken,
            Instant createdAt,
            Instant updatedAt,
            JobStatus status,
            String message,
            String agentId,
            String provider,
            String endpoint,
            String model
    ) {}

    private final Map<String, OnboardingJob> jobs = new ConcurrentHashMap<>();

    public OnboardingJob create() {
        String jobId = "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String token = "tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Instant now = Instant.now();
        OnboardingJob job = new OnboardingJob(jobId, token, now, now, JobStatus.DRAFT, "等待 Agent 提交接入信息", null, null, null, null);
        jobs.put(jobId, job);
        return job;
    }

    public Optional<OnboardingJob> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public Optional<OnboardingJob> updateStatus(
            String jobId,
            JobStatus status,
            String message,
            String agentId,
            String provider,
            String endpoint,
            String model
    ) {
        OnboardingJob old = jobs.get(jobId);
        if (old == null) {
            return Optional.empty();
        }
        OnboardingJob next = new OnboardingJob(
                old.jobId(),
                old.submitToken(),
                old.createdAt(),
                Instant.now(),
                status,
                message,
                agentId,
                provider,
                endpoint,
                model
        );
        jobs.put(jobId, next);
        return Optional.of(next);
    }
}
