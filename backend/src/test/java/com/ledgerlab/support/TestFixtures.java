package com.ledgerlab.support;

import com.ledgerlab.account.AccountService;
import com.ledgerlab.account.FinancialAccount;
import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.account.api.CreateAccountRequest;
import com.ledgerlab.auth.TokenService;
import com.ledgerlab.funds.FundsService;
import com.ledgerlab.funds.api.DepositRequest;
import com.ledgerlab.organization.AppUser;
import com.ledgerlab.organization.AppUserRepository;
import com.ledgerlab.organization.Membership;
import com.ledgerlab.organization.MembershipRepository;
import com.ledgerlab.organization.Organization;
import com.ledgerlab.organization.OrganizationRepository;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Role;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates isolated tenants, users, accounts and funds for integration tests. */
@TestComponent
public class TestFixtures {

    public static final String PASSWORD = "correct-horse-battery-staple";

    private final OrganizationRepository organizations;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AccountService accountService;
    private final FundsService fundsService;
    private final TransactionTemplate tx;

    public TestFixtures(
            OrganizationRepository organizations,
            AppUserRepository users,
            MembershipRepository memberships,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            AccountService accountService,
            FundsService fundsService,
            TransactionTemplate tx) {
        this.organizations = organizations;
        this.users = users;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.accountService = accountService;
        this.fundsService = fundsService;
        this.tx = tx;
    }

    public record Member(CurrentActor actor, String token) {
        public String email() {
            return actor.email();
        }
    }

    public record Tenant(UUID organizationId, Member admin, Member operations, Member viewer) {}

    public Tenant newTenant() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(status -> {
            Organization org = organizations.save(
                    new Organization(UUID.randomUUID(), "Test Org " + suffix, "test-" + suffix, Instant.now()));
            return new Tenant(
                    org.getId(),
                    member(org, "admin-" + suffix + "@example.test", Role.ADMIN),
                    member(org, "ops-" + suffix + "@example.test", Role.OPERATIONS),
                    member(org, "viewer-" + suffix + "@example.test", Role.VIEWER));
        });
    }

    private Member member(Organization org, String email, Role role) {
        AppUser user = users.save(
                new AppUser(UUID.randomUUID(), email, passwordEncoder.encode(PASSWORD), email, Instant.now()));
        memberships.save(new Membership(UUID.randomUUID(), org.getId(), user.getId(), role, Instant.now()));
        CurrentActor actor = new CurrentActor(user.getId(), org.getId(), role, email);
        String token = tokenService.issue(user.getId(), org.getId(), role, email).value();
        return new Member(actor, token);
    }

    public AccountResponse customer(Tenant tenant, String name) {
        return account(tenant, FinancialAccount.Type.CUSTOMER, name);
    }

    public AccountResponse merchant(Tenant tenant, String name) {
        return account(tenant, FinancialAccount.Type.MERCHANT, name);
    }

    public AccountResponse account(Tenant tenant, FinancialAccount.Type type, String name) {
        String reference = type.name().substring(0, 4) + "-" + UUID.randomUUID().toString().substring(0, 8);
        return accountService.create(tenant.operations().actor(), new CreateAccountRequest(type, name, reference));
    }

    public void deposit(Tenant tenant, UUID accountId, long amountMinor) {
        fundsService.deposit(
                tenant.operations().actor(), new DepositRequest(accountId, amountMinor, "test funding"), newKey());
    }

    public AccountResponse fundedCustomer(Tenant tenant, String name, long amountMinor) {
        AccountResponse customer = customer(tenant, name);
        deposit(tenant, customer.id(), amountMinor);
        return customer;
    }

    public static String newKey() {
        return "test-" + UUID.randomUUID();
    }
}
