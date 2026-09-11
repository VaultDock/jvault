package dev.jvault.jira.egress;

import dev.jvault.domain.common.Classification;
import dev.jvault.domain.common.SensitiveValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-CP-5 and FR-CP-6. The canary in these tests is a string that must never appear anywhere
 * outside the vault; several tests assert on its absence rather than on a positive outcome,
 * because "the leak did not happen" is the property that matters.
 */
class EgressGuardTest {

    private static final String CANARY =
            "Credential material observed in the process environment: "
                    + "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private final EgressGuard guard = EgressGuard.withDefaults();

    @Nested
    @DisplayName("externally-placed content must not appear in a Jira payload")
    class ExternalContentDetection {

        @Test
        @DisplayName("a clean payload passes and records which checks ran")
        void cleanPayloadPasses() {
            var request = request()
                    .field("summary", "[HIGH] Unexpected outbound connection from build agent")
                    .field("description", "Incident details are stored in jvault. Open: https://jvault.example.com/c/01J8ZQ")
                    .externalValue(SensitiveValue.of(CANARY, "description"))
                    .build();

            JiraSafePayload payload = guard.sanitise(request);

            assertThat(payload.textFields()).containsKey("summary");
            assertThat(payload.checksPerformed())
                    .contains("redaction-marker", "content-hash", "classifier:blocking");
        }

        @Test
        @DisplayName("the whole external value passed as a field value is caught")
        void wholeValueIsCaught() {
            var request = request()
                    .field("description", CANARY)
                    .externalValue(SensitiveValue.of(CANARY, "description"))
                    .build();

            assertThatThrownBy(() -> guard.sanitise(request))
                    .isInstanceOf(EgressViolationException.class)
                    .satisfies(e -> assertThat(((EgressViolationException) e).violations())
                            .extracting(EgressViolation::code)
                            .contains(EgressViolation.EXTERNAL_CONTENT_IN_PAYLOAD));
        }

        @Test
        @DisplayName("the external value embedded inside a larger string is caught")
        void containedLiteralIsCaught() {
            var request = request()
                    .field("description", "Context for the responder.\n\n" + CANARY + "\n\nEnds.")
                    .externalValue(SensitiveValue.of(CANARY, "description"))
                    .build();

            List<EgressViolation> violations = guard.check(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations.get(0).detail()).contains("CONTAINED_LITERAL");
        }

        @Test
        @DisplayName("a substantial excerpt of a long external document is caught")
        void sharedExcerptIsCaught() {
            String longNarrative = "Host build agent seven opened a transport layer security "
                    + "connection to an unfamiliar external endpoint shortly after the nightly "
                    + "pipeline began, and the process responsible was the continuous integration "
                    + "runner operating under an unexpected identity.";
            // An excerpt only — neither the whole value nor a short literal.
            String excerpt = "the process responsible was the continuous integration runner "
                    + "operating under an unexpected identity";

            var request = request()
                    .field("description", "Summary for Jira: " + excerpt + " (details withheld)")
                    .externalValue(SensitiveValue.of(longNarrative, "description"))
                    .build();

            assertThat(guard.check(request))
                    .extracting(EgressViolation::code)
                    .contains(EgressViolation.EXTERNAL_CONTENT_IN_PAYLOAD);
        }

        @Test
        @DisplayName("reformatting does not defeat the check")
        void normalisationResistsReformatting() {
            String original = "Affected account numbers are listed in the attached schedule "
                    + "for the European region";
            String reformatted = "AFFECTED   ACCOUNT NUMBERS\nARE LISTED IN THE ATTACHED\t"
                    + "SCHEDULE FOR THE EUROPEAN REGION";

            var request = request()
                    .field("description", reformatted)
                    .externalValue(SensitiveValue.of(original, "description"))
                    .build();

            assertThat(guard.check(request)).isNotEmpty();
        }

        @Test
        @DisplayName("a short external value is deliberately not hash-matched")
        void shortValuesAreNotHashMatched() {
            // Documented behaviour: below MIN_MATCH_LENGTH the hash check produces more false
            // positives than it prevents leaks, so short secrets are the classifier's job.
            // This test exists so the tradeoff is visible rather than accidental.
            var request = request()
                    .field("summary", "Ticket for user")
                    .externalValue(SensitiveValue.of("bob", "assignee"))
                    .build();

            assertThatCode(() -> guard.sanitise(request)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("every violating field is reported, not just the first")
        void allViolationsAreReported() {
            var request = request()
                    .field("description", CANARY)
                    .field("customfield_10010", CANARY)
                    .externalValue(SensitiveValue.of(CANARY, "description"))
                    .build();

            assertThat(guard.check(request))
                    .extracting(EgressViolation::fieldKey)
                    .containsExactlyInAnyOrder("description", "customfield_10010");
        }

        @Test
        @DisplayName("with no external parts the hash check is skipped, and says so")
        void noExternalPartsSkipsTheCheck() {
            var payload = guard.sanitise(request().field("summary", "Ordinary ticket").build());

            assertThat(payload.checksPerformed())
                    .contains("content-hash:skipped(no-external-parts)");
        }
    }

    @Nested
    @DisplayName("a stringified SensitiveValue is treated as a violation")
    class RedactionMarker {

        @Test
        @DisplayName("the redaction marker reaching a payload fails the write")
        void markerIsCaught() {
            var leaked = SensitiveValue.of(CANARY, "description");
            var request = request()
                    .field("description", "Details: " + leaked)   // implicit toString()
                    .build();

            assertThat(guard.check(request))
                    .extracting(EgressViolation::code)
                    .containsExactly(EgressViolation.SENSITIVE_VALUE_STRINGIFIED);
        }
    }

    @Nested
    @DisplayName("the classifier blocks at or above the threshold and advises below it")
    class Classifier {

        @Test
        @DisplayName("a private key in a RESTRICTED payload blocks the write")
        void blocksAtThreshold() {
            var request = request()
                    .classification(Classification.RESTRICTED)
                    .field("description", "-----BEGIN RSA PRIVATE KEY-----\nMIIEow...")
                    .build();

            assertThat(guard.check(request))
                    .extracting(EgressViolation::code)
                    .contains(EgressViolation.CLASSIFIED_CONTENT_DETECTED);
        }

        @Test
        @DisplayName("the same content in an INTERNAL payload is advisory, not blocking")
        void advisesBelowThreshold() {
            var request = request()
                    .classification(Classification.INTERNAL)
                    .field("description", "-----BEGIN RSA PRIVATE KEY-----\nMIIEow...")
                    .build();

            JiraSafePayload payload = guard.sanitise(request);

            assertThat(payload.checksPerformed())
                    .anyMatch(c -> c.startsWith("classifier:advisory-detections"));
        }

        @Test
        @DisplayName("the default pack recognises the credential shapes this system handles")
        void defaultPatternPack() {
            var classifier = PatternContentClassifier.withDefaults();

            assertThat(classifier.detect("AKIAIOSFODNN7EXAMPLE")).isNotEmpty();
            assertThat(classifier.detect("api_key = 1234567890abcdefghij")).isNotEmpty();
            assertThat(classifier.detect("-----BEGIN OPENSSH PRIVATE KEY-----")).isNotEmpty();
            assertThat(classifier.detect("Nothing sensitive in this sentence at all.")).isEmpty();
        }

        @Test
        @DisplayName("an environment-variable assignment is detected — \\b would miss it")
        void environmentVariableShapeIsDetected() {
            // The most common real leak shape. A word-boundary-based pattern never fires here,
            // because the keyword is surrounded by underscores.
            var classifier = PatternContentClassifier.withDefaults();

            assertThat(classifier.detect(
                    "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
                    .extracting(ContentClassifier.Detection::code)
                    .contains("SECRET_ASSIGNMENT");
            assertThat(classifier.detect("MYAPP_DB_PASSWORD: hunter2hunter2hunter2"))
                    .isNotEmpty();

            // Prose mentioning the word must not fire: an assignment is required.
            assertThat(classifier.detect("The token was rotated by the platform team on Tuesday."))
                    .isEmpty();
        }

        @Test
        @DisplayName("detections report a code and an offset, never the matched text")
        void detectionsCarryNoContent() {
            var detections = PatternContentClassifier.withDefaults()
                    .detect("token = " + CANARY);

            assertThat(detections).isNotEmpty();
            assertThat(detections.toString()).doesNotContain("wJalrXUtnFEMI");
        }
    }

    @Nested
    @DisplayName("nothing on the failure path carries the content that caused it")
    class FailurePathsCarryNoContent {

        @Test
        @DisplayName("the exception message, violations and payload never contain the canary")
        void canaryNeverEscapes() {
            var request = request()
                    .field("description", CANARY)
                    .field("summary", "token = " + CANARY)
                    .externalValue(SensitiveValue.of(CANARY, "description"))
                    .build();

            EgressViolationException thrown = null;
            try {
                guard.sanitise(request);
            } catch (EgressViolationException e) {
                thrown = e;
            }

            assertThat(thrown).as("the write should have been refused").isNotNull();

            // Every surface an operator or an API client could see.
            assertThat(thrown.getMessage()).doesNotContain(CANARY);
            assertThat(thrown.violations().toString()).doesNotContain(CANARY);
            assertThat(String.valueOf(thrown)).doesNotContain(CANARY);
            for (EgressViolation v : thrown.violations()) {
                assertThat(v.fieldKey() + v.code() + v.detail()).doesNotContain(CANARY);
            }
        }

        @Test
        @DisplayName("a safe payload's toString reports field names, never field values")
        void payloadToStringOmitsValues() {
            var payload = guard.sanitise(request()
                    .field("summary", "A perfectly ordinary summary line")
                    .build());

            assertThat(payload.toString())
                    .contains("summary")
                    .doesNotContain("A perfectly ordinary summary line");
        }
    }

    private static JiraWriteRequest.Builder request() {
        return JiraWriteRequest.builder(JiraOperation.CREATE_ISSUE, "01J8ZQK5M3T4X9YV2A0B7CDEFG")
                .classification(Classification.RESTRICTED);
    }
}
