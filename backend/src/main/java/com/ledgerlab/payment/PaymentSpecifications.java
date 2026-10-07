package com.ledgerlab.payment;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

final class PaymentSpecifications {

    private PaymentSpecifications() {}

    static Specification<Payment> matching(UUID organizationId, PaymentFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (filter.statuses() != null && !filter.statuses().isEmpty()) {
                predicates.add(root.get("status").in(filter.statuses()));
            }
            if (filter.customerAccountId() != null) {
                predicates.add(cb.equal(root.get("customerAccountId"), filter.customerAccountId()));
            }
            if (filter.merchantAccountId() != null) {
                predicates.add(cb.equal(root.get("merchantAccountId"), filter.merchantAccountId()));
            }
            if (filter.createdFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.createdFrom()));
            }
            if (filter.createdTo() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), filter.createdTo()));
            }
            if (filter.query() != null && !filter.query().isBlank()) {
                String q = filter.query().trim();
                UUID id = parseUuid(q);
                if (id != null) {
                    predicates.add(cb.equal(root.get("id"), id));
                } else {
                    predicates.add(cb.like(
                            cb.lower(root.get("reference")), "%" + q.toLowerCase(Locale.ROOT) + "%"));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
