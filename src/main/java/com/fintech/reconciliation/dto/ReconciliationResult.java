package com.fintech.reconciliation.dto;

import com.fintech.reconciliation.entity.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures the results of a reconciliation run.
 * Used for reporting, monitoring, and audit trails.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationResult {

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Builder.Default
    private int totalProcessed = 0;

    @Builder.Default
    private int successfullyReconciled = 0;

    @Builder.Default
    private int updatedToCompleted = 0;

    @Builder.Default
    private int updatedToFailed = 0;

    @Builder.Default
    private int stillPending = 0;

    @Builder.Default
    private int errors = 0;

    @Builder.Default
    private List<ReconciliationError> errorDetails = new ArrayList<>();

    /**
     * Individual reconciliation error details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReconciliationError {
        private Long transactionId;
        private String providerReference;
        private String errorMessage;
        private LocalDateTime occurredAt;
    }

    /**
     * Individual transaction reconciliation outcome.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionOutcome {
        private Long transactionId;
        private String providerReference;
        private TransactionStatus previousStatus;
        private TransactionStatus newStatus;
        private boolean updated;
        private String message;
    }

    public void incrementSuccessfullyReconciled() {
        this.successfullyReconciled++;
    }

    public void incrementUpdatedToCompleted() {
        this.updatedToCompleted++;
    }

    public void incrementUpdatedToFailed() {
        this.updatedToFailed++;
    }

    public void incrementStillPending() {
        this.stillPending++;
    }

    public void incrementErrors() {
        this.errors++;
    }

    public void addError(Long transactionId, String providerReference, String errorMessage) {
        this.errors++;
        if (this.errorDetails == null) {
            this.errorDetails = new ArrayList<>();
        }
        this.errorDetails.add(ReconciliationError.builder()
                .transactionId(transactionId)
                .providerReference(providerReference)
                .errorMessage(errorMessage)
                .occurredAt(LocalDateTime.now())
                .build());
    }

    public long getDurationMs() {
        if (startedAt == null || completedAt == null) {
            return 0;
        }
        return java.time.Duration.between(startedAt, completedAt).toMillis();
    }
}
