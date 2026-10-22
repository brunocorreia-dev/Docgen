package com.docgen.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Enforces retention: extracted attachment text is deleted when it expires
 * (default 30 days) and finished job records after 30 days.
 */
@Component
class RetentionJanitor {
    private static final Logger LOG = LoggerFactory.getLogger(RetentionJanitor.class);
    static final Duration JOB_RETENTION = Duration.ofDays(30);

    private final AttachmentRepository attachments;
    private final JobRepository jobs;
    private final Clock clock;

    RetentionJanitor(AttachmentRepository attachments, JobRepository jobs, Clock clock) {
        this.attachments = attachments;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    void purge() {
        Instant now = clock.instant();
        int expiredAttachments = attachments.deleteExpired(now);
        int oldJobs = jobs.deleteFinishedBefore(now.minus(JOB_RETENTION));
        if (expiredAttachments > 0 || oldJobs > 0) {
            LOG.info("Retention purge removed {} attachment(s) and {} job record(s)", expiredAttachments, oldJobs);
        }
    }
}
