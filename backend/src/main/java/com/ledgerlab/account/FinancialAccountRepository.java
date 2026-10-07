package com.ledgerlab.account;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, UUID> {

    Optional<FinancialAccount> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FinancialAccount a where a.id = :id and a.organizationId = :organizationId")
    Optional<FinancialAccount> lockByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndReference(UUID organizationId, String reference);

    @Query(
            """
            select a from FinancialAccount a
             where a.organizationId = :organizationId
               and (:type is null or a.type = :type)
               and (:status is null or a.status = :status)
               and (:q is null or lower(a.name) like :q or lower(a.reference) like :q)
            """)
    Page<FinancialAccount> search(
            UUID organizationId, FinancialAccount.Type type, FinancialAccount.Status status, String q, Pageable pageable);
}
