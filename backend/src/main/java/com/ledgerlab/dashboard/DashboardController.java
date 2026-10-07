package com.ledgerlab.dashboard;

import com.ledgerlab.dispute.Dispute;
import com.ledgerlab.dispute.DisputeRepository;
import com.ledgerlab.ledger.LedgerAccountPurpose;
import com.ledgerlab.ledger.LedgerService;
import com.ledgerlab.payment.PaymentFilter;
import com.ledgerlab.payment.PaymentRepository;
import com.ledgerlab.payment.PaymentService;
import com.ledgerlab.payment.PaymentStatus;
import com.ledgerlab.payment.api.PaymentResponse;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only projection for the overview page; owns no data. */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {

    private final LedgerService ledger;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final DisputeRepository disputes;

    public DashboardController(
            LedgerService ledger, PaymentRepository payments, PaymentService paymentService, DisputeRepository disputes) {
        this.ledger = ledger;
        this.payments = payments;
        this.paymentService = paymentService;
        this.disputes = disputes;
    }

    public record Summary(
            long customerAvailableMinor,
            long customerReservedMinor,
            long merchantAvailableMinor,
            long merchantDisputeHoldMinor,
            long externalClearingMinor,
            Map<PaymentStatus, Long> paymentsByStatus,
            long openDisputes,
            List<PaymentResponse> recentPayments) {}

    @GetMapping("/summary")
    @PreAuthorize(Roles.VIEWER)
    @Transactional(readOnly = true)
    @Operation(summary = "Key balances, payment counts and recent activity")
    public Summary summary(CurrentActor actor) {
        Map<LedgerAccountPurpose, Long> totals = ledger.totalsByPurpose(actor.organizationId());
        Map<PaymentStatus, Long> byStatus = new EnumMap<>(PaymentStatus.class);
        for (PaymentStatus status : PaymentStatus.values()) {
            byStatus.put(status, 0L);
        }
        for (Object[] row : payments.countByStatus(actor.organizationId())) {
            byStatus.put((PaymentStatus) row[0], (Long) row[1]);
        }
        var recent = paymentService
                .search(
                        actor.organizationId(),
                        new PaymentFilter(null, null, null, null, null, null),
                        PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .items();
        return new Summary(
                totals.getOrDefault(LedgerAccountPurpose.CUSTOMER_AVAILABLE, 0L),
                totals.getOrDefault(LedgerAccountPurpose.CUSTOMER_RESERVED, 0L),
                totals.getOrDefault(LedgerAccountPurpose.MERCHANT_AVAILABLE, 0L),
                totals.getOrDefault(LedgerAccountPurpose.MERCHANT_DISPUTE_HOLD, 0L),
                totals.getOrDefault(LedgerAccountPurpose.EXTERNAL_CLEARING, 0L),
                byStatus,
                disputes.countByOrganizationIdAndStatus(actor.organizationId(), Dispute.Status.OPEN),
                recent);
    }
}
