package com.fintech.reconciliation.service;

import com.fintech.reconciliation.dto.ProviderTransactionResponse;
import com.fintech.reconciliation.exception.ProviderApiException;

/**
 * Interface for communicating with external payment providers.
 * <p>
 * In production, implementations would call actual provider APIs:
 * - StripePaymentProviderClient
 * - AdyenPaymentProviderClient
 * - etc.
 * <p>
 * The mock implementation simulates provider behavior for testing.
 */
public interface PaymentProviderClient {

    /**
     * Fetches the current status of a transaction from the payment provider.
     *
     * @param providerReference The unique reference assigned by the provider
     * @return The provider's view of the transaction status
     * @throws ProviderApiException if the provider API is unavailable or returns an error
     */
    ProviderTransactionResponse getTransactionStatus(String providerReference)
            throws ProviderApiException;

    /**
     * Returns the name of this payment provider.
     * Used for logging and metrics.
     */
    String getProviderName();

    /**
     * Health check for the provider API.
     * Used by circuit breaker to determine if the provider is available.
     */
    boolean isAvailable();
}
