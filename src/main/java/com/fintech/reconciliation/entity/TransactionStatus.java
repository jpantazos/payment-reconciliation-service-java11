package com.fintech.reconciliation.entity;

/**
 * Represents the lifecycle status of a payment transaction.
 */
public enum TransactionStatus {
    /**
     * Transaction initiated but not yet confirmed by the provider.
     * This is the state that triggers reconciliation checks.
     */
    PENDING,

    /**
     * Transaction successfully processed and confirmed by the provider.
     */
    COMPLETED,

    /**
     * Transaction failed at the provider level.
     * Could be due to insufficient funds, fraud detection, or other reasons.
     */
    FAILED,

    /**
     * Transaction was refunded after being completed.
     */
    REFUNDED,

    /**
     * Transaction is disputed/chargebacked.
     */
    DISPUTED
}
