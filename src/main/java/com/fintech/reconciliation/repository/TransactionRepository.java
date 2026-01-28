package com.fintech.reconciliation.repository;

import com.fintech.reconciliation.entity.Transaction;
import com.fintech.reconciliation.entity.TransactionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Transaction entities with optimized queries for reconciliation.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Find all transactions with a specific status.
     * Used for batch processing during reconciliation.
     */
    List<Transaction> findByStatus(TransactionStatus status);

    /**
     * Find transactions with a specific status with pagination.
     * Essential for handling large volumes (1M+ records).
     */
    Page<Transaction> findByStatus(TransactionStatus status, Pageable pageable);

    /**
     * Find pending transactions that haven't been updated recently.
     * Avoids re-processing transactions that are already being reconciled.
     *
     * @param status        The transaction status to filter by
     * @param updatedBefore Only include transactions updated before this time
     * @param pageable      Pagination parameters
     */
    @Query("SELECT t FROM Transaction t WHERE t.status = :status " +
            "AND t.updatedAt < :updatedBefore " +
            "ORDER BY t.createdAt ASC")
    Page<Transaction> findStaleTransactionsByStatus(
            @Param("status") TransactionStatus status,
            @Param("updatedBefore") LocalDateTime updatedBefore,
            Pageable pageable
    );

    /**
     * Find transactions by status that have not exceeded max reconciliation attempts.
     * Prevents infinite retry loops for permanently failing transactions.
     */
    @Query("SELECT t FROM Transaction t WHERE t.status = :status " +
            "AND (t.reconciliationAttempts IS NULL OR t.reconciliationAttempts < :maxAttempts) " +
            "ORDER BY t.createdAt ASC")
    Page<Transaction> findByStatusWithMaxAttempts(
            @Param("status") TransactionStatus status,
            @Param("maxAttempts") int maxAttempts,
            Pageable pageable
    );

    /**
     * Find a transaction by provider reference with pessimistic lock.
     * Prevents concurrent modifications during reconciliation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.providerReference = :providerReference")
    Optional<Transaction> findByProviderReferenceWithLock(
            @Param("providerReference") String providerReference
    );

    /**
     * Find by provider reference without locking (for read-only operations).
     */
    Optional<Transaction> findByProviderReference(String providerReference);

    /**
     * Count transactions by status for metrics/monitoring.
     */
    long countByStatus(TransactionStatus status);

    /**
     * Bulk update status for multiple transactions (use with caution).
     * Useful for batch operations but bypasses optimistic locking.
     */
    @Modifying
    @Query("UPDATE Transaction t SET t.status = :newStatus, t.updatedAt = :now, " +
            "t.reconciledAt = :now WHERE t.id IN :ids AND t.status = :currentStatus")
    int bulkUpdateStatus(
            @Param("ids") List<Long> ids,
            @Param("currentStatus") TransactionStatus currentStatus,
            @Param("newStatus") TransactionStatus newStatus,
            @Param("now") LocalDateTime now
    );

    /**
     * Find transactions that need attention (too many failed reconciliation attempts).
     * These should be flagged for manual review.
     */
    @Query("SELECT t FROM Transaction t WHERE t.status = :status " +
            "AND t.reconciliationAttempts >= :minAttempts")
    List<Transaction> findTransactionsNeedingManualReview(
            @Param("status") TransactionStatus status,
            @Param("minAttempts") int minAttempts
    );

    /**
     * Get reconciliation statistics grouped by status.
     */
    @Query("SELECT t.status, COUNT(t) FROM Transaction t GROUP BY t.status")
    List<Object[]> getStatusCounts();
}
