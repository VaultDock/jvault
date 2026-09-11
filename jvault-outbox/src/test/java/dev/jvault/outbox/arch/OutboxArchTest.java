package dev.jvault.outbox.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.jvault.jira.egress.EgressGuard;
import dev.jvault.jira.gateway.JiraHttpClient;
import dev.jvault.jira.gateway.JiraWriteGateway;
import dev.jvault.outbox.OutboxDispatcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The egress rules from {@code EgressBoundaryArchTest} only see the classes on jvault-jira's
 * classpath, and jvault-outbox is downstream of it. These rules extend the same guarantees over
 * the module that actually performs Jira writes — which is where a bypass would be most tempting
 * and most damaging.
 */
class OutboxArchTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.jvault.outbox");
    }

    @Test
    @DisplayName("the outbox never reaches the Jira transport directly")
    void outboxDoesNotTouchTheTransport() {
        noClasses()
                .should().dependOnClassesThat().areAssignableTo(JiraHttpClient.class)
                .because("the outbox must go through JiraWriteGateway, which accepts only a "
                        + "payload the egress guard has produced")
                .check(classes);
    }

    @Test
    @DisplayName("only the dispatcher talks to the gateway")
    void onlyTheDispatcherWrites() {
        noClasses()
                .that().haveSimpleNameNotEndingWith("OutboxDispatcher")
                .should().dependOnClassesThat().areAssignableTo(JiraWriteGateway.class)
                .because("one component owning all Jira writes is what makes per-issue "
                        + "serialisation and the rate budget enforceable")
                .check(classes);
    }

    @Test
    @DisplayName("the dispatcher cannot write to Jira without the guard")
    void dispatcherDependsOnTheGuard() {
        classes()
                .that().haveSimpleName("OutboxDispatcher")
                .should().dependOnClassesThat().areAssignableTo(EgressGuard.class)
                .because("removing the guard from the dispatch path would compile fine and "
                        + "silently disable every content check")
                .check(classes);
    }

    @Test
    @DisplayName("the outbox stays free of Spring, so the dispatcher is testable without a container")
    void noSpringInTheOutbox() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .check(classes);
    }

    @Test
    @DisplayName("the ambiguity resolver cannot write to Jira")
    void ambiguityResolverIsReadOnly() {
        noClasses()
                .that().resideInAPackage("dev.jvault.outbox.ambiguity..")
                .should().dependOnClassesThat().areAssignableTo(JiraWriteGateway.class)
                .because("resolving an ambiguous creation must never itself create anything")
                .check(classes);
    }

    @Test
    @DisplayName("OutboxDispatcher is the only public entry point to dispatching")
    void dispatcherIsFinal() {
        org.assertj.core.api.Assertions
                .assertThat(java.lang.reflect.Modifier.isFinal(OutboxDispatcher.class.getModifiers()))
                .as("a subclass could override the guard call")
                .isTrue();
    }
}
