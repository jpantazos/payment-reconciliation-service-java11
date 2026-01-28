-- Initial test data for the reconciliation service
-- Insert some pending transactions that need reconciliation
INSERT INTO transactions (amount, currency, status, provider_reference, provider_name, created_at, updated_at, reconciliation_attempts)
VALUES
    -- These should be updated to COMPLETED (mock provider returns SUCCESSFUL)
    (100.00, 'USD', 'PENDING', 'PROV-001', 'MockProvider', CURRENT_TIMESTAMP - INTERVAL '2' HOUR, CURRENT_TIMESTAMP - INTERVAL '1' HOUR, 0),
    (250.50, 'EUR', 'PENDING', 'PROV-002', 'MockProvider', CURRENT_TIMESTAMP - INTERVAL '3' HOUR, CURRENT_TIMESTAMP - INTERVAL '2' HOUR, 0),
    (1000.00, 'GBP', 'PENDING', 'PROV-003', 'MockProvider', CURRENT_TIMESTAMP - INTERVAL '4' HOUR, CURRENT_TIMESTAMP - INTERVAL '3' HOUR, 0),
    
    -- These should be updated to FAILED (mock provider returns FAILED)
    (500.00, 'USD', 'PENDING', 'PROV-004', 'MockProvider', CURRENT_TIMESTAMP - INTERVAL '1' HOUR, CURRENT_TIMESTAMP - INTERVAL '30' MINUTE, 0),
    (75.00, 'EUR', 'PENDING', 'PROV-005', 'MockProvider', CURRENT_TIMESTAMP - INTERVAL '2' HOUR, CURRENT_TIMESTAMP - INTERVAL '1' HOUR, 0),
    
    -- This should remain PENDING (mock provider returns PROCESSING)
    (200.00, 'USD', 'PENDING', 'PROV-006', 'MockProvider', CURRENT_TIMESTAMP - INTERVAL '30' MINUTE, CURRENT_TIMESTAMP - INTERVAL '15' MINUTE, 0),
    
    -- This should be updated to REFUNDED (mock provider returns REFUNDED)
    (150.00, 'USD', 'PENDING', 'PROV-007', 'MockProvider', CURRENT_TIMESTAMP - INTERVAL '5' HOUR, CURRENT_TIMESTAMP - INTERVAL '4' HOUR, 0),
    
    -- Some transactions not in provider (should get NOT_FOUND status)
    (300.00, 'USD', 'PENDING', 'UNKNOWN-001', 'MockProvider', CURRENT_TIMESTAMP - INTERVAL '6' HOUR, CURRENT_TIMESTAMP - INTERVAL '5' HOUR, 0),
    (450.00, 'EUR', 'PENDING', 'UNKNOWN-002', 'MockProvider', CURRENT_TIMESTAMP - INTERVAL '7' HOUR, CURRENT_TIMESTAMP - INTERVAL '6' HOUR, 0);

-- Insert some already completed/failed transactions (won't be reconciled)
INSERT INTO transactions (amount, currency, status, provider_reference, provider_name, created_at, updated_at, reconciled_at, reconciliation_attempts)
VALUES
    (800.00, 'USD', 'COMPLETED', 'PROV-100', 'MockProvider', CURRENT_TIMESTAMP - INTERVAL '1' DAY, CURRENT_TIMESTAMP - INTERVAL '23' HOUR, CURRENT_TIMESTAMP - INTERVAL '23' HOUR, 1),
    (125.00, 'EUR', 'FAILED', 'PROV-101', 'MockProvider', CURRENT_TIMESTAMP - INTERVAL '2' DAY, CURRENT_TIMESTAMP - INTERVAL '47' HOUR, CURRENT_TIMESTAMP - INTERVAL '47' HOUR, 1);
