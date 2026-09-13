package dev.jvault.ingest.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Kafka is a way messages arrive, not what they mean.
 *
 * <p>The design rests on one claim: a ticket created from a Kafka event and one created over
 * HTTP go through the same rules, so placement, classification and the egress guard cannot
 * differ between them. That holds only while the deciding code has no idea which door the
 * message came through — and it stops holding the first time something in {@code processing}
 * reaches for a {@code ConsumerRecord} because it was convenient.
 */
class IngestBoundaryArchTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.jvault.ingest");
    }

    @Test
    @DisplayName("the processing core knows nothing about Kafka")
    void processingIsTransportAgnostic() {
        noClasses()
                .that().resideInAnyPackage("dev.jvault.ingest.processing..",
                        "dev.jvault.ingest.mapping..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.apache.kafka..", "org.springframework.kafka..")
                .because("a ticket from a Kafka event and one from an HTTP request must go "
                        + "through the same rules, which stops being true the moment the rules "
                        + "can tell the difference")
                .check(classes);
    }

    @Test
    @DisplayName("the processing core knows nothing about Spring either")
    void processingIsFrameworkAgnostic() {
        noClasses()
                .that().resideInAnyPackage("dev.jvault.ingest.processing..",
                        "dev.jvault.ingest.mapping..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("the same reasoning as the transport: application logic that needs a "
                        + "framework to run needs that framework to be tested")
                .check(classes);
    }

    @Test
    @DisplayName("the Kafka adapter does not reach past the processor into content or Jira")
    void adapterGoesThroughTheProcessor() {
        noClasses()
                .that().resideInAPackage("dev.jvault.ingest.kafka..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "dev.jvault.jira..", "dev.jvault.storage..", "dev.jvault.crypto..")
                .because("the adapter's job is to hand over a message, and an adapter that can "
                        + "reach Jira directly is one that can bypass the placement rules")
                .check(classes);
    }
}
