package com.fintech.reconciliation.scheduler;

import com.fintech.reconciliation.dto.ReconciliationResult;
import com.fintech.reconciliation.exception.ReconciliationException;
import com.fintech.reconciliation.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Scheduler for automated reconciliation runs.
 * <p>
 * The reconciliation frequency should be tuned based on:
 * - Volume of transactions
 * - Business SLAs for status updates
 * - Provider API rate limits
 * <p>
 * Default: Every 5 minutes
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;

    @Value("${reconciliation.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    /**
     * Scheduled reconciliation job.
     * <p>
     * Uses fixedDelay to ensure the next run doesn't start until
     * the previous one completes (prevents overlap).
     * <p>
     * Cron expression can be used for more complex schedules:
     * e.g., @Scheduled(cron = 0 5 * * * *) for every 5 minutes
     */
    @Scheduled(fixedDelayString = "${reconciliation.scheduler.interval-ms:300000}")
    public void runScheduledReconciliation() {
        if (!schedulerEnabled) {
            log.debug("Scheduler is disabled, skipping reconciliation run");
            return;
        }

        log.info("Starting scheduled reconciliation at {}", LocalDateTime.now());

        try {
            ReconciliationResult result = reconciliationService.reconcilePendingTransactions();

            logResult(result);

            // Alert if there are too many errors
            if (result.getErrors() > result.getTotalProcessed() * 0.1) {
                log.warn("High error rate detected in reconciliation: {} errors out of {} processed",
                        result.getErrors(), result.getTotalProcessed());
                // In production: Send alert to monitoring system (PagerDuty, Slack, etc.)
            }

        } catch (ReconciliationException e) {
            log.warn("Reconciliation skipped: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Scheduled reconciliation failed with unexpected error", e);
            // In production: Send alert to monitoring system
        }
    }

    private void logResult(ReconciliationResult result) {
        if (result.getTotalProcessed() == 0) {
            log.info("No pending transactions to reconcile");
        } else {
            log.info("Reconciliation completed in {}ms: {} processed, {} completed, {} failed, {} errors",
                    result.getDurationMs(),
                    result.getTotalProcessed(),
                    result.getUpdatedToCompleted(),
                    result.getUpdatedToFailed(),
                    result.getErrors());
        }
    }
}
