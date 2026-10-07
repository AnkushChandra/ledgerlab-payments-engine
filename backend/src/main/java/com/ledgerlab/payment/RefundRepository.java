package com.ledgerlab.payment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    @Query(
            """
            select r from Refund r
             where r.paymentId = :paymentId and r.organizationId = :organizationId
             order by r.createdAt asc, r.id asc
            """)
    List<Refund> forPayment(UUID organizationId, UUID paymentId);
}
