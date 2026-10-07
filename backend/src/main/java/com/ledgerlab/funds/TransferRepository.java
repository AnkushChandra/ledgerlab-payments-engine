package com.ledgerlab.funds;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    @Query(
            """
            select t from Transfer t
             where t.organizationId = :organizationId
               and (:accountId is null or t.sourceAccountId = :accountId or t.destinationAccountId = :accountId)
            """)
    Page<Transfer> search(UUID organizationId, UUID accountId, Pageable pageable);
}
