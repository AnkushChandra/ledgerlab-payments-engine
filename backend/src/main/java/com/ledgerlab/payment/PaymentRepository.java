package com.ledgerlab.payment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * {@code SELECT ... FOR UPDATE}: every mutation of a payment starts here, so operations on the
     * same payment execute one at a time and always see the latest committed amounts.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id and p.organizationId = :organizationId")
    Optional<Payment> lockByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Payment> findByOrganizationIdAndIdIn(UUID organizationId, List<UUID> ids);

    @Query(
            """
            select p from Payment p
             where p.organizationId = :organizationId
               and p.capturedAmountMinor > 0
               and p.firstCapturedAt >= :from and p.firstCapturedAt < :to
            """)
    List<Payment> findCapturedBetween(UUID organizationId, Instant from, Instant to);

    @Query("select p.status, count(p) from Payment p where p.organizationId = :organizationId group by p.status")
    List<Object[]> countByStatus(UUID organizationId);
}
