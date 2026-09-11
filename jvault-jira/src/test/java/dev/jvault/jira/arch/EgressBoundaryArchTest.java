package dev.jvault.jira.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.jira.egress.JiraSafePayload;
import dev.jvault.jira.gateway.JiraHttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural enforcement of the leak boundary (docs/03-architecture.md 3.3.3).
 *
 * <p>These rules are the reason "every Jira write is sanitised" can be stated as a fact. Without
 * them it is a convention, and the whole design rests on it holding after refactors nobody has
 * thought of yet. If one of these fails, the right response is almost never to relax the rule.
 */
class EgressBoundaryArchTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.jvault");
    }

    @Test
    @DisplayName("only the gateway package may reach the Jira HTTP transport")
    void onlyGatewayTalksToJira() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("dev.jvault.jira.gateway..")
                .should().dependOnClassesThat().areAssignableTo(JiraHttpClient.class)
                .because("there must be exactly one place where bytes can reach Jira, so that "
                        + "the egress guard cannot be bypassed");

        rule.check(classes);
    }

    @Test
    @DisplayName("the gateway never handles sensitive values")
    void gatewayNeverTouchesSensitiveValues() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("dev.jvault.jira.gateway..")
                .should().dependOnClassesThat().areAssignableTo(SensitiveValue.class)
                .because("by the time a payload reaches the gateway it must already be safe; "
                        + "a gateway that can see a SensitiveValue is a gateway that can send one");

        rule.check(classes);
    }

    @Test
    @DisplayName("the domain stays free of Jira and of Spring")
    void domainHasNoOutwardDependencies() {
        noClasses()
                .that().resideInAPackage("dev.jvault.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("dev.jvault.jira..")
                .because("the placement engine must stay a pure function so its precedence "
                        + "rules remain exhaustively testable (FR-CP-2)")
                .check(classes);

        noClasses()
                .that().resideInAPackage("dev.jvault.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .because("domain logic must be testable without a container")
                .check(classes);
    }

    @Test
    @DisplayName("the dependency runs gateway -> egress, never the reverse")
    void egressDoesNotDependOnGateway() {
        noClasses()
                .that().resideInAPackage("dev.jvault.jira.egress..")
                .should().dependOnClassesThat().resideInAnyPackage("dev.jvault.jira.gateway..")
                .because("the guard must be usable, and testable, without a Jira transport")
                .check(classes);
    }

    @Test
    @DisplayName("JiraSafePayload cannot be constructed from outside the egress package")
    void safePayloadHasNoPublicConstructor() {
        Constructor<?>[] constructors = JiraSafePayload.class.getDeclaredConstructors();

        assertThat(constructors)
                .as("JiraSafePayload must have exactly one constructor, so there is one way in")
                .hasSize(1);

        assertThat(Modifier.isPublic(constructors[0].getModifiers()))
                .as("a public constructor on JiraSafePayload would let any caller assert that a "
                        + "payload is safe without it having been checked — which removes the "
                        + "entire guarantee the type exists to provide")
                .isFalse();

        assertThat(Modifier.isProtected(constructors[0].getModifiers()))
                .as("a protected constructor would let a subclass in another package do the same")
                .isFalse();
    }

    @Test
    @DisplayName("JiraSafePayload is final, so it cannot be subclassed into something unchecked")
    void safePayloadIsFinal() {
        assertThat(Modifier.isFinal(JiraSafePayload.class.getModifiers())).isTrue();
    }
}
