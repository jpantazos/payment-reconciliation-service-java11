package com.fintech.reconciliation.exception;

/**
 * Thrown when communication with the payment provider API fails.
 * This could be due to network issues, timeouts, or provider downtime.
 */
public class ProviderApiException extends ReconciliationException {

    private final String providerName;
    private final String providerReference;
    private final boolean isRetryable;

    public ProviderApiException(String message, String providerName) {
        super(message);
        this.providerName = providerName;
        this.providerReference = null;
        this.isRetryable = true;
    }

    public ProviderApiException(String message, String providerName, String providerReference) {
        super(message);
        this.providerName = providerName;
        this.providerReference = providerReference;
        this.isRetryable = true;
    }

    public ProviderApiException(String message, String providerName, String providerReference,
                                boolean isRetryable) {
        super(message);
        this.providerName = providerName;
        this.providerReference = providerReference;
        this.isRetryable = isRetryable;
    }

    public ProviderApiException(String message, String providerName, Throwable cause) {
        super(message, cause);
        this.providerName = providerName;
        this.providerReference = null;
        this.isRetryable = true;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getProviderReference() {
        return providerReference;
    }

    /**
     * Indicates if this error is transient and the operation can be retried.
     * Non-retryable errors include: invalid references, authentication failures.
     */
    public boolean isRetryable() {
        return isRetryable;
    }
}
