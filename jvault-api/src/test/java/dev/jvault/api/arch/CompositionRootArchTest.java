package dev.jvault.api.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The composition root is allowed to know which adapter implements which port. Nothing else is.
 *
 * <p>That rule is what lets the same application core serve the REST API and the Kafka consumer,
 * and what makes a second storage backend or a fourth database engine a matter of adding a class
 * rather than of finding every place that assumed the first one. It is also the kind of rule that
 * decays quietly: one controller reaching for a {@code Jdbc...} type compiles perfectly well.
 */
class CompositionRootArchTest {

    private static JavaClasses apiClasses;

    @BeforeAll
    static void importClasses() {
        apiClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.jvault.api");
    }

    @Test
    @DisplayName("only the composition root knows about persistence adapters")
    void onlyConfigurationTouchesPersistence() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("dev.jvault.api.config..")
                .should().dependOnClassesThat().resideInAPackage("dev.jvault.persistence..")
                .because("a controller that names a Jdbc type has decided which database this "
                        + "runs on, and nothing above the composition root gets to decide that");

        rule.check(apiClasses);
    }

    @Test
    @DisplayName("only the composition root knows which storage backend is in use")
    void onlyConfigurationTouchesStorageImplementations() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("dev.jvault.api.config..")
                .should().dependOnClassesThat().resideInAPackage("dev.jvault.storage.filesystem..")
                .because("content on a filesystem in development and in S3 in production has to "
                        + "be the same code path, or the one that matters is the untested one");

        rule.check(apiClasses);
    }

    @Test
    @DisplayName("the development-only identity resolver is reachable from nowhere but the root")
    void developmentAuthIsNotReachable() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("dev.jvault.api.config..")
                .and().haveSimpleNameNotEndingWith("HeaderCallerResolver")
                .should().dependOnClassesThat()
                .haveSimpleName("HeaderCallerResolver")
                .because("a resolver that trusts a header is one import away from being used by "
                        + "something that is not a development profile");

        rule.check(apiClasses);
    }
}
