package com.fintech.reconciliation.service;

import com.fintech.reconciliation.dto.ProviderTransactionResponse;
import com.fintech.reconciliation.dto.ProviderTransactionResponse.ProviderStatus;
import com.fintech.reconciliation.dto.ReconciliationResult;
import com.fintech.reconciliation.entity.Transaction;
import com.fintech.reconciliation.entity.TransactionStatus;
import com.fintech.reconciliation.exception.ProviderApiException;
import com.fintech.reconciliation.exception.ReconciliationException;
import com.fintech.reconciliation.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core reconciliation service that identifies and resolves discrepancies
 * between our internal ledger and external payment providers.
 * <p>
 * Key Design Decisions:
 * 1. Pagination: Processes transactions in batches to handle large volumes
 * 2. Idempotency: Uses optimistic locking to prevent duplicate updates
 * 3. Resilience: Continues processing even if individual transactions fail
 * 4. Observability: Emits metrics for monitoring reconciliation health
 */
@Service
@Slf4j
public class ReconciliationService {

    private final TransactionRepository transactionRepository;
    private final PaymentProviderClient providerClient;
    private final MeterRegistry meterRegistry;

    @Value("${reconciliation.batch-size:100}")
    private int batchSize;

    @Value("${reconciliation.max-attempts:5}")
    private int maxReconciliationAttempts;

    @Value("${reconciliation.stale-threshold-minutes:5}")
    private int staleThresholdMinutes;

    // Metrics
    private Counter reconciliationCounter;
    private Counter successCounter;
    private Counter failureCounter;
    private Counter providerErrorCounter;
    private Timer reconciliationTimer;

    // Prevents concurrent reconciliation runs
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public ReconciliationService(TransactionRepository transactionRepository,
                                 PaymentProviderClient providerClient,
                                 MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.providerClient = providerClient;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initMetrics() {
        reconciliationCounter = Counter.builder("reconciliation.transactions.total")
                .description("Total transactions processed for reconciliation")
                .register(meterRegistry);

        successCounter = Counter.builder("reconciliation.transactions.success")
                .description("Transactions successfully reconciled")
                .register(meterRegistry);

        failureCounter = Counter.builder("reconciliation.transactions.failure")
                .description("Transactions that failed reconciliation")
                .register(meterRegistry);

        providerErrorCounter = Counter.builder("reconciliation.provider.errors")
                .description("Errors communicating with payment provider")
                .register(meterRegistry);

        reconciliationTimer = Timer.builder("reconciliation.duration")
                .description("Time taken to complete reconciliation run")
                .register(meterRegistry);
    }

    /**
     * Main reconciliation entry point.
     * Processes all pending transactions in batches.
     *
     * @return ReconciliationResult containing statistics about the run
     */
    public ReconciliationResult reconcilePendingTransactions() {
        // Prevent concurrent runs
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("Reconciliation already in progress, skipping this run");
            throw new ReconciliationException("Reconciliation already in progress");
        }

        ReconciliationResult result = ReconciliationResult.builder()
                .startedAt(LocalDateTime.now())
                .build();

        log.info("Starting reconciliation of pending transactions");

        try {
            return reconciliationTimer.record(() -> {
                processAllPendingTransactions(result);
                result.setCompletedAt(LocalDateTime.now());

                log.info("Reconciliation completed. Processed: {}, Updated to COMPLETED: {}, " +
                                "Updated to FAILED: {}, Still Pending: {}, Errors: {}",
                        result.getTotalProcessed(),
                        result.getUpdatedToCompleted(),
                        result.getUpdatedToFailed(),
                        result.getStillPending(),
                        result.getErrors());

                return result;
            });
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * Process all pending transactions using pagination.
     * This approach handles millions of records efficiently.
     */
    private void processAllPendingTransactions(ReconciliationResult result) {
        int pageNumber = 0;
        Page<Transaction> page;

        LocalDateTime staleThreshold = LocalDateTime.now().minusMinutes(staleThresholdMinutes);

        do {
            Pageable pageable = PageRequest.of(pageNumber, batchSize);

            // Fetch transactions that haven't exceeded max attempts
            page = transactionRepository.findByStatusWithMaxAttempts(
                    TransactionStatus.PENDING,
                    maxReconciliationAttempts,
                    pageable
            );

            log.debug("Processing page {} with {} transactions",
                    pageNumber, page.getNumberOfElements());

            for (Transaction transaction : page.getContent()) {
                processTransaction(transaction, result);
            }

            pageNumber++;

            // Safety check: prevent infinite loops
            if (pageNumber > 10000) {
                log.warn("Reached maximum page limit (10000), stopping reconciliation");
                break;
            }

        } while (page.hasNext());
    }

    /**
     * Process a single transaction.
     * Handles all error cases gracefully to ensure other transactions can still be processed.
     */
    private void processTransaction(Transaction transaction, ReconciliationResult result) {
        result.setTotalProcessed(result.getTotalProcessed() + 1);
        reconciliationCounter.increment();

        try {
            reconcileTransaction(transaction);

            // Check what happened to the transaction
            Transaction updated = transactionRepository.findById(transaction.getId())
                    .orElse(transaction);

            switch (updated.getStatus()) {
                case COMPLETED:
                    result.incrementUpdatedToCompleted();
                    result.incrementSuccessfullyReconciled();
                    successCounter.increment();
                    break;
                case FAILED:
                    result.incrementUpdatedToFailed();
                    result.incrementSuccessfullyReconciled();
                    successCounter.increment();
                    break;
                case PENDING:
                    result.incrementStillPending();
                    break;
                default:
                    // Other statuses are handled
                    break;
            }

        } catch (ProviderApiException e) {
            handleProviderError(transaction, e, result);
        } catch (Exception e) {
            handleUnexpectedError(transaction, e, result);
        }
    }

    /**
     * Core reconciliation logic for a single transaction.
     * Uses optimistic locking to ensure idempotent updates.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reconcileTransaction(Transaction transaction) {
        log.debug("Reconciling transaction {} with provider reference {}",
                transaction.getId(), transaction.getProviderReference());

        // Increment attempt counter
        transaction.incrementReconciliationAttempts();

        // Fetch status from provider
        ProviderTransactionResponse providerResponse =
                providerClient.getTransactionStatus(transaction.getProviderReference());

        // Map provider status to our internal status
        TransactionStatus newStatus = mapProviderStatus(providerResponse.getStatus());

        if (newStatus != null && newStatus != transaction.getStatus()) {
            log.info("Updating transaction {} from {} to {} based on provider status {}",
                    transaction.getId(),
                    transaction.getStatus(),
                    newStatus,
                    providerResponse.getStatus());

            transaction.setStatus(newStatus);
            transaction.setReconciledAt(LocalDateTime.now());

            // Store error info if transaction failed
            if (newStatus == TransactionStatus.FAILED) {
                transaction.setLastError(String.format("%s: %s",
                        providerResponse.getErrorCode(),
                        providerResponse.getErrorMessage()));
            }
        } else if (providerResponse.getStatus() == ProviderStatus.PROCESSING) {
            log.debug("Transaction {} still processing at provider", transaction.getId());
        } else if (providerResponse.getStatus() == ProviderStatus.NOT_FOUND) {
            log.warn("Transaction {} not found at provider. Reference: {}",
                    transaction.getId(), transaction.getProviderReference());
            transaction.setLastError("Transaction not found at provider");
        }

        // Save changes (version check ensures idempotency)
        transactionRepository.save(transaction);
    }

    /**
     * Map provider status to our internal transaction status.
     * Returns null if no status change is needed.
     */
    private TransactionStatus mapProviderStatus(ProviderStatus providerStatus) {
        if (providerStatus == null) {
            return null;
        }

        return switch (providerStatus) {
            case SUCCESSFUL -> TransactionStatus.COMPLETED;
            case FAILED -> TransactionStatus.FAILED;
            case REFUNDED -> TransactionStatus.REFUNDED;
            case PROCESSING, NOT_FOUND -> null; // No change
        };
    }

    /**
     * Handle errors from the payment provider.
     * Updates the transaction with error details but doesn't fail the entire batch.
     */
    private void handleProviderError(Transaction transaction, ProviderApiException e,
                                     ReconciliationResult result) {
        log.warn("Provider API error for transaction {}: {}",
                transaction.getId(), e.getMessage());

        providerErrorCounter.increment();
        result.addError(transaction.getId(), transaction.getProviderReference(), e.getMessage());

        // Update transaction with error info
        try {
            transaction.incrementReconciliationAttempts();
            transaction.setLastError(e.getMessage());
            transactionRepository.save(transaction);
        } catch (Exception saveError) {
            log.error("Failed to save error state for transaction {}",
                    transaction.getId(), saveError);
        }
    }

    /**
     * Handle unexpected errors during reconciliation.
     */
    private void handleUnexpectedError(Transaction transaction, Exception e,
                                       ReconciliationResult result) {
        log.error("Unexpected error reconciling transaction {}: {}",
                transaction.getId(), e.getMessage(), e);

        failureCounter.increment();
        result.addError(transaction.getId(), transaction.getProviderReference(),
                "Unexpected error: " + e.getMessage());

        try {
            transaction.incrementReconciliationAttempts();
            transaction.setLastError("Unexpected error: " + e.getMessage());
            transactionRepository.save(transaction);
        } catch (Exception saveError) {
            log.error("Failed to save error state for transaction {}",
                    transaction.getId(), saveError);
        }
    }

    /**
     * Get current reconciliation statistics.
     * Useful for monitoring dashboards.
     */
    public ReconciliationStats getStats() {
        return ReconciliationStats.builder()
                .pendingCount(transactionRepository.countByStatus(TransactionStatus.PENDING))
                .completedCount(transactionRepository.countByStatus(TransactionStatus.COMPLETED))
                .failedCount(transactionRepository.countByStatus(TransactionStatus.FAILED))
                .isReconciliationRunning(isRunning.get())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class ReconciliationStats {
        private long pendingCount;
        private long completedCount;
        private long failedCount;
        private boolean isReconciliationRunning;
    }
}
