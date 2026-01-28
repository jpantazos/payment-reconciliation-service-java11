package com.fintech.reconciliation.controller;

import com.fintech.reconciliation.dto.ReconciliationResult;
import com.fintech.reconciliation.entity.Transaction;
import com.fintech.reconciliation.entity.TransactionStatus;
import com.fintech.reconciliation.repository.TransactionRepository;
import com.fintech.reconciliation.service.ReconciliationService;
import com.fintech.reconciliation.service.ReconciliationService.ReconciliationStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for reconciliation operations.
 * <p>
 * Provides endpoints for:
 * - Triggering manual reconciliation
 * - Viewing reconciliation statistics
 * - Managing transactions (for testing/admin)
 */
@RestController
@RequestMapping("/api/v1/reconciliation")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Reconciliation", description = "Payment reconciliation operations API")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final TransactionRepository transactionRepository;

    @Operation(
            summary = "Trigger manual reconciliation",
            description = "Triggers a manual reconciliation run. Useful for testing, recovery after outages, or on-demand reconciliation before end-of-day processing."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reconciliation completed successfully",
                    content = @Content(schema = @Schema(implementation = ReconciliationResult.class))),
            @ApiResponse(responseCode = "409", description = "Reconciliation already in progress")
    })
    @PostMapping("/run")
    public ResponseEntity<ReconciliationResult> triggerReconciliation() {
        log.info("Manual reconciliation triggered via API");
        ReconciliationResult result = reconciliationService.reconcilePendingTransactions();
        return ResponseEntity.ok(result);
    }

    @Operation(
            summary = "Get reconciliation statistics",
            description = "Returns current reconciliation statistics including pending, completed, and failed transaction counts."
    )
    @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully",
            content = @Content(schema = @Schema(implementation = ReconciliationStats.class)))
    @GetMapping("/stats")
    public ResponseEntity<ReconciliationStats> getStats() {
        return ResponseEntity.ok(reconciliationService.getStats());
    }

    @Operation(
            summary = "Get pending transactions",
            description = "Returns a paginated list of transactions with PENDING status."
    )
    @ApiResponse(responseCode = "200", description = "Pending transactions retrieved successfully")
    @GetMapping("/transactions/pending")
    public ResponseEntity<Page<Transaction>> getPendingTransactions(
            @Parameter(description = "Page number (0-indexed)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {

        Page<Transaction> transactions = transactionRepository.findByStatus(
                TransactionStatus.PENDING,
                PageRequest.of(page, size)
        );
        return ResponseEntity.ok(transactions);
    }

    @Operation(
            summary = "Get transactions needing manual review",
            description = "Returns transactions that have exceeded the maximum reconciliation attempts and require manual review."
    )
    @ApiResponse(responseCode = "200", description = "Transactions needing review retrieved successfully")
    @GetMapping("/transactions/needs-review")
    public ResponseEntity<List<Transaction>> getTransactionsNeedingReview(
            @Parameter(description = "Minimum reconciliation attempts threshold") @RequestParam(defaultValue = "5") int minAttempts) {

        List<Transaction> transactions = transactionRepository.findTransactionsNeedingManualReview(
                TransactionStatus.PENDING,
                minAttempts
        );
        return ResponseEntity.ok(transactions);
    }

    @Operation(
            summary = "Get transaction by ID",
            description = "Returns a single transaction by its unique identifier."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Transaction found",
                    content = @Content(schema = @Schema(implementation = Transaction.class))),
            @ApiResponse(responseCode = "404", description = "Transaction not found")
    })
    @GetMapping("/transactions/{id}")
    public ResponseEntity<Transaction> getTransaction(
            @Parameter(description = "Transaction ID") @PathVariable Long id) {
        return transactionRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Health check",
            description = "Returns the health status of the reconciliation service. Used by load balancers and monitoring systems."
    )
    @ApiResponse(responseCode = "200", description = "Service is healthy")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        ReconciliationStats stats = reconciliationService.getStats();

        Map<String, Object> health = Map.of(
                "status", "UP",
                "reconciliation", Map.of(
                        "isRunning", stats.isReconciliationRunning(),
                        "pendingTransactions", stats.getPendingCount(),
                        "completedTransactions", stats.getCompletedCount(),
                        "failedTransactions", stats.getFailedCount()
                )
        );

        return ResponseEntity.ok(health);
    }
}
