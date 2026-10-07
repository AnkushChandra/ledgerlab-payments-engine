package com.ledgerlab.dispute;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    Optional<Dispute> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dispute d where d.id = :id and d.organizationId = :organizationId")
    Optional<Dispute> lockByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query(
            """
            select d from Dispute d
             where d.organizationId = :organizationId
               and (:status is null or d.status = :status)
            """)
    Page<Dispute> search(UUID organizationId, Dispute.Status status, Pageable pageable);

    List<Dispute> findByOrganizationIdAndPaymentIdOrderByOpenedAtDesc(UUID organizationId, UUID paymentId);

    long countByOrganizationIdAndStatus(UUID organizationId, Dispute.Status status);
}
