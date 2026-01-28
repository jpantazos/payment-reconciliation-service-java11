package com.fintech.reconciliation.exception;

/**
 * Base exception for reconciliation-related errors.
 */
public class ReconciliationException extends RuntimeException {

    public ReconciliationException(String message) {
        super(message);
    }

    public ReconciliationException(String message, Throwable cause) {
        super(message, cause);
    }
}
