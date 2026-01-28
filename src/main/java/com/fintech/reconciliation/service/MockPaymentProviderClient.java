package com.fintech.reconciliation.service;

import com.fintech.reconciliation.dto.ProviderTransactionResponse;
import com.fintech.reconciliation.dto.ProviderTransactionResponse.ProviderStatus;
import com.fintech.reconciliation.exception.ProviderApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock implementation of a payment provider client.
 * <p>
 * Simulates realistic provider behavior including:
 * - Transaction status lookups
 * - Intermittent failures (for testing resilience)
 * - Network latency simulation
 * <p>
 * In production, this would be replaced with actual provider integrations.
 */
@Service
@Slf4j
public class MockPaymentProviderClient implements PaymentProviderClient {

    private static final String PROVIDER_NAME = "MockProvider";

    // Simulated database of provider transaction states
    private final Map<String, ProviderTransactionResponse> mockDatabase = new ConcurrentHashMap<>();

    private final Random random = new Random();

    @Value("${provider.mock.failure-rate:0.1}")
    private double failureRate;

    @Value("${provider.mock.latency-ms:50}")
    private int latencyMs;

    private volatile boolean simulateOutage = false;

    public MockPaymentProviderClient() {
        initializeMockData();
    }

    /**
     * Pre-populate some test data for demonstration.
     */
    private void initializeMockData() {
        // Successful transactions
        addMockTransaction("PROV-001", ProviderStatus.SUCCESSFUL, new BigDecimal("100.00"), "USD");
        addMockTransaction("PROV-002", ProviderStatus.SUCCESSFUL, new BigDecimal("250.50"), "EUR");
        addMockTransaction("PROV-003", ProviderStatus.SUCCESSFUL, new BigDecimal("1000.00"), "GBP");

        // Failed transactions
        addMockTransaction("PROV-004", ProviderStatus.FAILED, new BigDecimal("500.00"), "USD");
        addMockTransaction("PROV-005", ProviderStatus.FAILED, new BigDecimal("75.00"), "EUR");

        // Still processing
        addMockTransaction("PROV-006", ProviderStatus.PROCESSING, new BigDecimal("200.00"), "USD");

        // Refunded
        addMockTransaction("PROV-007", ProviderStatus.REFUNDED, new BigDecimal("150.00"), "USD");

        log.info("Mock provider initialized with {} transactions", mockDatabase.size());
    }

    private void addMockTransaction(String reference, ProviderStatus status,
                                    BigDecimal amount, String currency) {
        mockDatabase.put(reference, ProviderTransactionResponse.builder()
                .providerReference(reference)
                .status(status)
                .amount(amount)
                .currency(currency)
                .processedAt(LocalDateTime.now().minusHours(random.nextInt(24)))
                .errorCode(status == ProviderStatus.FAILED ? "INSUFFICIENT_FUNDS" : null)
                .errorMessage(status == ProviderStatus.FAILED ? "Card declined" : null)
                .build());
    }

    @Override
    @CircuitBreaker(name = "providerApi", fallbackMethod = "getTransactionStatusFallback")
    @Retryable(
            retryFor = ProviderApiException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public ProviderTransactionResponse getTransactionStatus(String providerReference)
            throws ProviderApiException {

        log.debug("Fetching transaction status from provider for reference: {}", providerReference);

        // Simulate network latency
        simulateLatency();

        // Simulate provider outage
        if (simulateOutage) {
            throw new ProviderApiException(
                    "Provider API is currently unavailable",
                    PROVIDER_NAME,
                    providerReference,
                    true
            );
        }

        // Simulate random failures (network issues, timeouts)
        if (random.nextDouble() < failureRate) {
            throw new ProviderApiException(
                    "Simulated network failure while contacting provider",
                    PROVIDER_NAME,
                    providerReference,
                    true
            );
        }

        // Look up in mock database
        ProviderTransactionResponse response = mockDatabase.get(providerReference);

        if (response == null) {
            log.warn("Transaction not found in provider: {}", providerReference);
            return ProviderTransactionResponse.builder()
                    .providerReference(providerReference)
                    .status(ProviderStatus.NOT_FOUND)
                    .build();
        }

        log.debug("Provider returned status {} for reference {}",
                response.getStatus(), providerReference);

        return response;
    }

    /**
     * Fallback method when circuit breaker is open.
     * This prevents cascading failures when the provider is consistently failing.
     */
    public ProviderTransactionResponse getTransactionStatusFallback(
            String providerReference, Throwable throwable) {
        log.warn("Circuit breaker triggered for provider reference: {}. Error: {}",
                providerReference, throwable.getMessage());

        throw new ProviderApiException(
                "Provider API circuit breaker is open. Service temporarily unavailable.",
                PROVIDER_NAME,
                providerReference,
                true
        );
    }

    private void simulateLatency() {
        if (latencyMs > 0) {
            try {
                Thread.sleep(random.nextInt(latencyMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        return !simulateOutage;
    }

    // Methods for testing/simulation control

    /**
     * Add a transaction to the mock database.
     * Useful for testing specific scenarios.
     */
    public void addTransaction(String reference, ProviderStatus status,
                               BigDecimal amount, String currency) {
        addMockTransaction(reference, status, amount, currency);
    }

    /**
     * Update the status of a transaction in the mock database.
     * Simulates the real-world scenario where provider status changes.
     */
    public void updateTransactionStatus(String reference, ProviderStatus newStatus) {
        ProviderTransactionResponse existing = mockDatabase.get(reference);
        if (existing != null) {
            mockDatabase.put(reference, ProviderTransactionResponse.builder()
                    .providerReference(reference)
                    .status(newStatus)
                    .amount(existing.getAmount())
                    .currency(existing.getCurrency())
                    .processedAt(LocalDateTime.now())
                    .build());
        }
    }

    /**
     * Simulate a provider outage for testing resilience.
     */
    public void setSimulateOutage(boolean outage) {
        this.simulateOutage = outage;
        log.info("Provider outage simulation set to: {}", outage);
    }

    /**
     * Clear all mock data.
     */
    public void clearMockData() {
        mockDatabase.clear();
    }
}
