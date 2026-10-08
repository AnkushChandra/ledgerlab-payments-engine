package com.ledgerlab;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Enforces the module dependency rules documented in docs/architecture.md. */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ledgerlab");
    }

    @Test
    void ledgerDependsOnlyOnShared() {
        noClasses()
                .that()
                .resideInAPackage("com.ledgerlab.ledger..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.ledgerlab.account..",
                        "com.ledgerlab.funds..",
                        "com.ledgerlab.payment..",
                        "com.ledgerlab.dispute..",
                        "com.ledgerlab.reconciliation..",
                        "com.ledgerlab.organization..",
                        "com.ledgerlab.auth..",
                        "com.ledgerlab.audit..")
                .check(classes);
    }

    @Test
    void sharedDependsOnNoBusinessModule() {
        noClasses()
                .that()
                .resideInAPackage("com.ledgerlab.shared..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.ledgerlab.account..",
                        "com.ledgerlab.ledger..",
                        "com.ledgerlab.funds..",
                        "com.ledgerlab.payment..",
                        "com.ledgerlab.dispute..",
                        "com.ledgerlab.reconciliation..",
                        "com.ledgerlab.organization..",
                        "com.ledgerlab.auth..",
                        "com.ledgerlab.audit..")
                .check(classes);
    }

    @Test
    void paymentDoesNotDependOnDisputeOrReconciliation() {
        noClasses()
                .that()
                .resideInAPackage("com.ledgerlab.payment..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.ledgerlab.dispute..", "com.ledgerlab.reconciliation..")
                .check(classes);
    }

    @Test
    void controllersLiveInApiPackagesAndDoNotUseRepositories() {
        classes()
                .that()
                .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .and()
                .resideOutsideOfPackage("com.ledgerlab.dashboard..")
                .should()
                .resideInAPackage("..api..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(org.springframework.data.repository.Repository.class)
                .check(classes);
    }

    @Test
    void servicesDoNotDependOnControllers() {
        noClasses()
                .that()
                .haveSimpleNameEndingWith("Service")
                .should()
                .dependOnClassesThat()
                .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .check(classes);
    }
}
