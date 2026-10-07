package com.ledgerlab.organization;

import com.ledgerlab.shared.security.Role;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

    Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    List<Membership> findByOrganizationIdOrderByCreatedAtAscIdAsc(UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.id = :id and m.organizationId = :organizationId")
    Optional<Membership> lockByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.organizationId = :organizationId and m.role = :role")
    List<Membership> lockByOrganizationIdAndRole(UUID organizationId, Role role);
}
