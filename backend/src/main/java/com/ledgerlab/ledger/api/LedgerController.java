package com.ledgerlab.ledger.api;

import com.ledgerlab.ledger.IntegrityReport;
import com.ledgerlab.ledger.LedgerService;
import com.ledgerlab.ledger.LedgerTransactionView;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
@Tag(name = "Ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/transactions/{transactionId}")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Get an immutable journal with all of its entries")
    public LedgerTransactionView transaction(CurrentActor actor, @PathVariable UUID transactionId) {
        return ledgerService.getTransaction(actor.organizationId(), transactionId);
    }

    @GetMapping("/integrity")
    @PreAuthorize(Roles.VIEWER)
    @Operation(
            summary = "Verify ledger invariants from raw entries",
            description = "Checks that every journal balances, every cached balance equals the sum of its "
                    + "entries, and the clearing asset equals total liabilities.")
    public IntegrityReport integrity(CurrentActor actor) {
        return ledgerService.integrity(actor.organizationId());
    }
}
