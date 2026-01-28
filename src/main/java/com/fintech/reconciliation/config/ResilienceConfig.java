package com.fintech.reconciliation.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration for Resilience4j Circuit Breaker.
 * <p>
 * The circuit breaker protects against cascading failures when
 * the payment provider API is experiencing issues.
 * <p>
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Provider is failing, requests fail fast
 * - HALF_OPEN: Testing if provider has recovered
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                // Number of calls to record before calculating failure rate
                .slidingWindowSize(10)
                // Failure rate threshold to open the circuit (50%)
                .failureRateThreshold(50)
                // Time to wait before transitioning from OPEN to HALF_OPEN
                .waitDurationInOpenState(Duration.ofSeconds(30))
                // Number of calls permitted in HALF_OPEN state
                .permittedNumberOfCallsInHalfOpenState(3)
                // Automatically transition to HALF_OPEN after wait duration
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        return CircuitBreakerRegistry.of(config);
    }
}
