package com.ledgerlab.funds;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DepositRepository extends JpaRepository<Deposit, UUID> {

    @Query(
            """
            select d from Deposit d
             where d.organizationId = :organizationId
               and (:accountId is null or d.accountId = :accountId)
            """)
    Page<Deposit> search(UUID organizationId, UUID accountId, Pageable pageable);
}
