package com.fintech.reconciliation.integration;

import com.fintech.reconciliation.dto.ReconciliationResult;
import com.fintech.reconciliation.entity.Transaction;
import com.fintech.reconciliation.entity.TransactionStatus;
import com.fintech.reconciliation.repository.TransactionRepository;
import com.fintech.reconciliation.service.MockPaymentProviderClient;
import com.fintech.reconciliation.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the reconciliation service.
 * 
 * These tests verify the full reconciliation flow with a real database
 * (H2 in-memory) and the mock provider.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReconciliationIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private MockPaymentProviderClient mockProvider;

    @BeforeEach
    void setUp() {
        // Clear existing data
        transactionRepository.deleteAll();
        mockProvider.clearMockData();
        
        // Set up provider to not simulate failures for tests
        mockProvider.setSimulateOutage(false);
    }

    @Test
    @DisplayName("Full reconciliation flow - updates pending transactions correctly")
    void fullReconciliationFlow() {
        // Given: Set up test transactions and mock provider responses
        
        // Transaction that should become COMPLETED
        Transaction pendingSuccess = createTransaction("TEST-SUCCESS", TransactionStatus.PENDING);
        transactionRepository.save(pendingSuccess);
        mockProvider.addTransaction("TEST-SUCCESS", 
            com.fintech.reconciliation.dto.ProviderTransactionResponse.ProviderStatus.SUCCESSFUL,
            new BigDecimal("100.00"), "USD");
        
        // Transaction that should become FAILED
        Transaction pendingFailed = createTransaction("TEST-FAILED", TransactionStatus.PENDING);
        transactionRepository.save(pendingFailed);
        mockProvider.addTransaction("TEST-FAILED",
            com.fintech.reconciliation.dto.ProviderTransactionResponse.ProviderStatus.FAILED,
            new BigDecimal("200.00"), "EUR");
        
        // Transaction that should remain PENDING
        Transaction pendingProcessing = createTransaction("TEST-PROCESSING", TransactionStatus.PENDING);
        transactionRepository.save(pendingProcessing);
        mockProvider.addTransaction("TEST-PROCESSING",
            com.fintech.reconciliation.dto.ProviderTransactionResponse.ProviderStatus.PROCESSING,
            new BigDecimal("300.00"), "GBP");

        // When: Run reconciliation
        ReconciliationResult result = reconciliationService.reconcilePendingTransactions();

        // Then: Verify results
        assertThat(result.getTotalProcessed()).isEqualTo(3);
        assertThat(result.getUpdatedToCompleted()).isEqualTo(1);
        assertThat(result.getUpdatedToFailed()).isEqualTo(1);
        assertThat(result.getStillPending()).isEqualTo(1);
        assertThat(result.getErrors()).isEqualTo(0);
        
        // Verify database state
        Transaction successTx = transactionRepository.findByProviderReference("TEST-SUCCESS")
            .orElseThrow();
        assertThat(successTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(successTx.getReconciledAt()).isNotNull();
        
        Transaction failedTx = transactionRepository.findByProviderReference("TEST-FAILED")
            .orElseThrow();
        assertThat(failedTx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        
        Transaction processingTx = transactionRepository.findByProviderReference("TEST-PROCESSING")
            .orElseThrow();
        assertThat(processingTx.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    @DisplayName("Should not process already completed transactions")
    void shouldNotProcessCompletedTransactions() {
        // Given
        Transaction completed = createTransaction("TEST-COMPLETED", TransactionStatus.COMPLETED);
        transactionRepository.save(completed);
        
        Transaction pending = createTransaction("TEST-PENDING", TransactionStatus.PENDING);
        transactionRepository.save(pending);
        mockProvider.addTransaction("TEST-PENDING",
            com.fintech.reconciliation.dto.ProviderTransactionResponse.ProviderStatus.SUCCESSFUL,
            new BigDecimal("100.00"), "USD");

        // When
        ReconciliationResult result = reconciliationService.reconcilePendingTransactions();

        // Then: Only pending transaction was processed
        assertThat(result.getTotalProcessed()).isEqualTo(1);
        
        // Completed transaction unchanged
        Transaction unchanged = transactionRepository.findByProviderReference("TEST-COMPLETED")
            .orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    @DisplayName("Should handle provider outage gracefully")
    void shouldHandleProviderOutageGracefully() {
        // Given
        Transaction pending = createTransaction("TEST-OUTAGE", TransactionStatus.PENDING);
        transactionRepository.save(pending);
        
        // Simulate provider outage
        mockProvider.setSimulateOutage(true);

        // When
        ReconciliationResult result = reconciliationService.reconcilePendingTransactions();

        // Then: Transaction still pending but error recorded
        assertThat(result.getTotalProcessed()).isEqualTo(1);
        assertThat(result.getErrors()).isEqualTo(1);
        
        Transaction tx = transactionRepository.findByProviderReference("TEST-OUTAGE")
            .orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.getReconciliationAttempts()).isEqualTo(1);
        assertThat(tx.getLastError()).isNotNull();
    }

    @Test
    @DisplayName("Should track reconciliation attempts")
    void shouldTrackReconciliationAttempts() {
        // Given
        Transaction pending = createTransaction("TEST-ATTEMPTS", TransactionStatus.PENDING);
        pending.setReconciliationAttempts(2);
        transactionRepository.save(pending);
        
        mockProvider.addTransaction("TEST-ATTEMPTS",
            com.fintech.reconciliation.dto.ProviderTransactionResponse.ProviderStatus.PROCESSING,
            new BigDecimal("100.00"), "USD");

        // When
        reconciliationService.reconcilePendingTransactions();

        // Then
        Transaction tx = transactionRepository.findByProviderReference("TEST-ATTEMPTS")
            .orElseThrow();
        assertThat(tx.getReconciliationAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("Statistics should reflect current state")
    void statisticsShouldReflectCurrentState() {
        // Given
        transactionRepository.save(createTransaction("TX-1", TransactionStatus.PENDING));
        transactionRepository.save(createTransaction("TX-2", TransactionStatus.PENDING));
        transactionRepository.save(createTransaction("TX-3", TransactionStatus.COMPLETED));
        transactionRepository.save(createTransaction("TX-4", TransactionStatus.FAILED));

        // When
        var stats = reconciliationService.getStats();

        // Then
        assertThat(stats.getPendingCount()).isEqualTo(2);
        assertThat(stats.getCompletedCount()).isEqualTo(1);
        assertThat(stats.getFailedCount()).isEqualTo(1);
    }

    private Transaction createTransaction(String providerRef, TransactionStatus status) {
        return Transaction.builder()
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .status(status)
            .providerReference(providerRef)
            .providerName("MockProvider")
            .createdAt(LocalDateTime.now().minusHours(1))
            .updatedAt(LocalDateTime.now().minusMinutes(30))
            .reconciliationAttempts(0)
            .build();
    }
}
