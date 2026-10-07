package com.ledgerlab.organization.api;

import com.ledgerlab.organization.OrganizationService;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Role;
import com.ledgerlab.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organization")
@Tag(name = "Organization")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @GetMapping
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Get the organization of the current token")
    public OrganizationResponse current(CurrentActor actor) {
        return organizationService.getOrganization(actor.organizationId());
    }

    @GetMapping("/memberships")
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "List members and their roles")
    public List<MembershipResponse> memberships(CurrentActor actor) {
        return organizationService.listMemberships(actor.organizationId());
    }

    @PostMapping("/memberships")
    @PreAuthorize(Roles.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a member, creating the user when the email is new")
    public MembershipResponse createMembership(CurrentActor actor, @Valid @RequestBody CreateMembershipRequest request) {
        return organizationService.createMembership(actor, request);
    }

    @PatchMapping("/memberships/{membershipId}")
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Change a member's role (the last ADMIN cannot be demoted)")
    public MembershipResponse changeRole(
            CurrentActor actor, @PathVariable UUID membershipId, @Valid @RequestBody ChangeRoleRequest request) {
        return organizationService.changeRole(actor, membershipId, request.role());
    }

    public record ChangeRoleRequest(@NotNull Role role) {}
}
