package com.fintech.reconciliation.service;

import com.fintech.reconciliation.dto.ProviderTransactionResponse;
import com.fintech.reconciliation.dto.ProviderTransactionResponse.ProviderStatus;
import com.fintech.reconciliation.dto.ReconciliationResult;
import com.fintech.reconciliation.entity.Transaction;
import com.fintech.reconciliation.entity.TransactionStatus;
import com.fintech.reconciliation.exception.ProviderApiException;
import com.fintech.reconciliation.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReconciliationService.
 * 
 * Tests cover:
 * - Status mapping from provider to internal
 * - Error handling
 * - Idempotency
 * - Batch processing
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PaymentProviderClient providerClient;

    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        reconciliationService = new ReconciliationService(
            transactionRepository, 
            providerClient, 
            meterRegistry
        );
        reconciliationService.initMetrics();
    }

    @Nested
    @DisplayName("Status Mapping Tests")
    class StatusMappingTests {

        @Test
        @DisplayName("Should update PENDING to COMPLETED when provider returns SUCCESSFUL")
        void shouldUpdateToCompletedWhenProviderSuccessful() {
            // Given
            Transaction transaction = createPendingTransaction("PROV-001");
            
            when(providerClient.getTransactionStatus("PROV-001"))
                .thenReturn(createProviderResponse("PROV-001", ProviderStatus.SUCCESSFUL));
            when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            reconciliationService.reconcileTransaction(transaction);

            // Then
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            
            Transaction saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(saved.getReconciledAt()).isNotNull();
        }

        @Test
        @DisplayName("Should update PENDING to FAILED when provider returns FAILED")
        void shouldUpdateToFailedWhenProviderFailed() {
            // Given
            Transaction transaction = createPendingTransaction("PROV-002");
            
            ProviderTransactionResponse response = ProviderTransactionResponse.builder()
                .providerReference("PROV-002")
                .status(ProviderStatus.FAILED)
                .errorCode("INSUFFICIENT_FUNDS")
                .errorMessage("Card declined")
                .build();
            
            when(providerClient.getTransactionStatus("PROV-002"))
                .thenReturn(response);
            when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            reconciliationService.reconcileTransaction(transaction);

            // Then
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            
            Transaction saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TransactionStatus.FAILED);
            assertThat(saved.getLastError()).contains("INSUFFICIENT_FUNDS");
        }

        @Test
        @DisplayName("Should keep PENDING when provider returns PROCESSING")
        void shouldKeepPendingWhenProviderProcessing() {
            // Given
            Transaction transaction = createPendingTransaction("PROV-003");
            
            when(providerClient.getTransactionStatus("PROV-003"))
                .thenReturn(createProviderResponse("PROV-003", ProviderStatus.PROCESSING));
            when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            reconciliationService.reconcileTransaction(transaction);

            // Then
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            
            Transaction saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        @DisplayName("Should update to REFUNDED when provider returns REFUNDED")
        void shouldUpdateToRefundedWhenProviderRefunded() {
            // Given
            Transaction transaction = createPendingTransaction("PROV-004");
            
            when(providerClient.getTransactionStatus("PROV-004"))
                .thenReturn(createProviderResponse("PROV-004", ProviderStatus.REFUNDED));
            when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            reconciliationService.reconcileTransaction(transaction);

            // Then
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            
            Transaction saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        }
    }

    @Nested
    @DisplayName("Batch Processing Tests")
    class BatchProcessingTests {

        @Test
        @DisplayName("Should process all pending transactions in batches")
        void shouldProcessAllPendingTransactionsInBatches() {
            // Given
            List<Transaction> batch1 = Arrays.asList(
                createPendingTransaction("PROV-001"),
                createPendingTransaction("PROV-002")
            );
            
            Page<Transaction> page1 = new PageImpl<>(batch1);
            Page<Transaction> emptyPage = new PageImpl<>(Collections.emptyList());
            
            when(transactionRepository.findByStatusWithMaxAttempts(
                eq(TransactionStatus.PENDING), anyInt(), any(Pageable.class)))
                .thenReturn(page1)
                .thenReturn(emptyPage);
            
            when(providerClient.getTransactionStatus(anyString()))
                .thenReturn(createProviderResponse("any", ProviderStatus.SUCCESSFUL));
            
            when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
            
            when(transactionRepository.findById(anyLong()))
                .thenAnswer(invocation -> {
                    Transaction t = createPendingTransaction("test");
                    t.setStatus(TransactionStatus.COMPLETED);
                    return Optional.of(t);
                });

            // When
            ReconciliationResult result = reconciliationService.reconcilePendingTransactions();

            // Then
            assertThat(result.getTotalProcessed()).isEqualTo(2);
            verify(providerClient, times(2)).getTransactionStatus(anyString());
        }

        @Test
        @DisplayName("Should return empty result when no pending transactions")
        void shouldReturnEmptyResultWhenNoPendingTransactions() {
            // Given
            Page<Transaction> emptyPage = new PageImpl<>(Collections.emptyList());
            
            when(transactionRepository.findByStatusWithMaxAttempts(
                eq(TransactionStatus.PENDING), anyInt(), any(Pageable.class)))
                .thenReturn(emptyPage);

            // When
            ReconciliationResult result = reconciliationService.reconcilePendingTransactions();

            // Then
            assertThat(result.getTotalProcessed()).isEqualTo(0);
            assertThat(result.getErrors()).isEqualTo(0);
            verify(providerClient, never()).getTransactionStatus(anyString());
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should continue processing when provider throws exception for one transaction")
        void shouldContinueProcessingOnProviderException() {
            // Given
            List<Transaction> transactions = Arrays.asList(
                createPendingTransaction("PROV-FAIL"),
                createPendingTransaction("PROV-SUCCESS")
            );
            
            Page<Transaction> page = new PageImpl<>(transactions);
            Page<Transaction> emptyPage = new PageImpl<>(Collections.emptyList());
            
            when(transactionRepository.findByStatusWithMaxAttempts(
                eq(TransactionStatus.PENDING), anyInt(), any(Pageable.class)))
                .thenReturn(page)
                .thenReturn(emptyPage);
            
            // First call fails, second succeeds
            when(providerClient.getTransactionStatus("PROV-FAIL"))
                .thenThrow(new ProviderApiException("API timeout", "MockProvider"));
            when(providerClient.getTransactionStatus("PROV-SUCCESS"))
                .thenReturn(createProviderResponse("PROV-SUCCESS", ProviderStatus.SUCCESSFUL));
            
            when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
            
            when(transactionRepository.findById(anyLong()))
                .thenAnswer(invocation -> {
                    Transaction t = createPendingTransaction("test");
                    t.setStatus(TransactionStatus.COMPLETED);
                    return Optional.of(t);
                });

            // When
            ReconciliationResult result = reconciliationService.reconcilePendingTransactions();

            // Then
            assertThat(result.getTotalProcessed()).isEqualTo(2);
            assertThat(result.getErrors()).isEqualTo(1);
            assertThat(result.getSuccessfullyReconciled()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should handle NOT_FOUND response from provider")
        void shouldHandleNotFoundResponse() {
            // Given
            Transaction transaction = createPendingTransaction("UNKNOWN-REF");
            
            when(providerClient.getTransactionStatus("UNKNOWN-REF"))
                .thenReturn(createProviderResponse("UNKNOWN-REF", ProviderStatus.NOT_FOUND));
            when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            reconciliationService.reconcileTransaction(transaction);

            // Then
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            
            Transaction saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TransactionStatus.PENDING);
            assertThat(saved.getLastError()).contains("not found");
        }
    }

    @Nested
    @DisplayName("Idempotency Tests")
    class IdempotencyTests {

        @Test
        @DisplayName("Should increment reconciliation attempts on each run")
        void shouldIncrementReconciliationAttempts() {
            // Given
            Transaction transaction = createPendingTransaction("PROV-001");
            transaction.setReconciliationAttempts(2);
            
            when(providerClient.getTransactionStatus("PROV-001"))
                .thenReturn(createProviderResponse("PROV-001", ProviderStatus.PROCESSING));
            when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            reconciliationService.reconcileTransaction(transaction);

            // Then
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            
            Transaction saved = captor.getValue();
            assertThat(saved.getReconciliationAttempts()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Statistics Tests")
    class StatisticsTests {

        @Test
        @DisplayName("Should return correct statistics")
        void shouldReturnCorrectStatistics() {
            // Given
            when(transactionRepository.countByStatus(TransactionStatus.PENDING)).thenReturn(10L);
            when(transactionRepository.countByStatus(TransactionStatus.COMPLETED)).thenReturn(100L);
            when(transactionRepository.countByStatus(TransactionStatus.FAILED)).thenReturn(5L);

            // When
            var stats = reconciliationService.getStats();

            // Then
            assertThat(stats.getPendingCount()).isEqualTo(10L);
            assertThat(stats.getCompletedCount()).isEqualTo(100L);
            assertThat(stats.getFailedCount()).isEqualTo(5L);
        }
    }

    // Helper methods

    private Transaction createPendingTransaction(String providerReference) {
        return Transaction.builder()
            .id(providerReference.hashCode() & 0xFFFFFFFFL)
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .status(TransactionStatus.PENDING)
            .providerReference(providerReference)
            .providerName("MockProvider")
            .createdAt(LocalDateTime.now().minusHours(1))
            .updatedAt(LocalDateTime.now().minusMinutes(30))
            .reconciliationAttempts(0)
            .build();
    }

    private ProviderTransactionResponse createProviderResponse(String reference, ProviderStatus status) {
        return ProviderTransactionResponse.builder()
            .providerReference(reference)
            .status(status)
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .processedAt(LocalDateTime.now())
            .build();
    }
}
