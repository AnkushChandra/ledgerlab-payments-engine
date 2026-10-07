package com.ledgerlab.payment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    @Query(
            """
            select e from PaymentEvent e
             where e.paymentId = :paymentId and e.organizationId = :organizationId
             order by e.createdAt asc, e.id asc
            """)
    List<PaymentEvent> timeline(UUID organizationId, UUID paymentId);
}
