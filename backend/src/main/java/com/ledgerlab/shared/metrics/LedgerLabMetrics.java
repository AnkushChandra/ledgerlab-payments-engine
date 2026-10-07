package com.ledgerlab.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Business metrics. HTTP latency and error counts come from Spring's http.server.requests timer. */
@Component
public class LedgerLabMetrics {

    private final MeterRegistry registry;

    public LedgerLabMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void paymentOperation(String operation, String outcome) {
        Counter.builder("ledgerlab.payments.operations")
                .description("Payment operations by outcome")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void idempotency(String outcome) {
        Counter.builder("ledgerlab.idempotency.outcomes")
                .description("Idempotent request outcomes (executed, replayed, conflict)")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void reconciliationResults(String classification, long count) {
        Counter.builder("ledgerlab.reconciliation.results")
                .description("Reconciliation results by classification")
                .tag("classification", classification)
                .register(registry)
                .increment(count);
    }

    public void authentication(String outcome) {
        Counter.builder("ledgerlab.auth.logins")
                .description("Login attempts by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
