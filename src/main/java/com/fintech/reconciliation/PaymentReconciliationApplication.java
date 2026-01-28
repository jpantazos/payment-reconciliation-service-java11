package com.fintech.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payment Reconciliation Service
 * <p>
 * This service identifies and resolves discrepancies between our internal ledger
 * and external payment providers (e.g., Stripe, Adyen).
 * <p>
 * Key Features:
 * - Scheduled reconciliation of PENDING transactions
 * - Resilient provider API communication with retry and circuit breaker
 * - Idempotent transaction updates
 * - Comprehensive metrics and logging
 */
@SpringBootApplication
@EnableScheduling
@EnableRetry
public class PaymentReconciliationApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentReconciliationApplication.class, args);
    }
}
