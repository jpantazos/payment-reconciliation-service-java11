package com.fintech.reconciliation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Represents the response from an external payment provider's status API.
 * This DTO maps to what providers like Stripe or Adyen would return.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderTransactionResponse {

    /**
     * The provider's unique reference for this transaction.
     * e.g., Stripe's payment_intent ID or Adyen's pspReference
     */
    private String providerReference;

    /**
     * The status as reported by the provider.
     * We map this to our internal TransactionStatus.
     */
    private ProviderStatus status;

    /**
     * The transaction amount as confirmed by the provider.
     * Used for amount reconciliation (detecting partial captures, etc.)
     */
    private BigDecimal amount;

    /**
     * The currency code (ISO 4217).
     */
    private String currency;

    /**
     * Timestamp when the provider processed this transaction.
     */
    private LocalDateTime processedAt;

    /**
     * Optional error code if the transaction failed.
     */
    private String errorCode;

    /**
     * Optional error message with details about the failure.
     */
    private String errorMessage;

    /**
     * Provider-specific status values.
     * Different providers use different terminology.
     */
    public enum ProviderStatus {
        /**
         * Payment was successful (maps to COMPLETED)
         */
        SUCCESSFUL,

        /**
         * Payment failed (maps to FAILED)
         */
        FAILED,

        /**
         * Payment is still processing (keep as PENDING)
         */
        PROCESSING,

        /**
         * Transaction not found at provider
         */
        NOT_FOUND,

        /**
         * Transaction was refunded
         */
        REFUNDED
    }
}
