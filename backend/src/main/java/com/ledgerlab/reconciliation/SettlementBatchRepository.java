package com.ledgerlab.reconciliation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, UUID> {

    Optional<SettlementBatch> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<SettlementBatch> findByOrganizationIdAndContentSha256(UUID organizationId, String contentSha256);

    Page<SettlementBatch> findByOrganizationId(UUID organizationId, Pageable pageable);
}
