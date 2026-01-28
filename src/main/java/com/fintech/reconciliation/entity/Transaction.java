package com.fintech.reconciliation.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Represents a payment transaction in our internal ledger.
 * <p>
 * The provider_reference is the unique identifier from the external payment provider
 * (e.g., Stripe payment_intent ID, Adyen pspReference).
 */
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_provider_reference", columnList = "provider_reference", unique = true),
        @Index(name = "idx_status_updated_at", columnList = "status, updated_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "provider_reference", nullable = false, unique = true, length = 100)
    private String providerReference;

    @Column(name = "provider_name", length = 50)
    private String providerName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    @Column(name = "reconciliation_attempts")
    @Builder.Default
    private Integer reconciliationAttempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Increments the reconciliation attempt counter.
     * Used for tracking retry attempts and potential dead-letter scenarios.
     */
    public void incrementReconciliationAttempts() {
        this.reconciliationAttempts = (this.reconciliationAttempts == null ? 0 : this.reconciliationAttempts) + 1;
    }
}
