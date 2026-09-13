package dev.jvault.api;

import dev.jvault.api.jira.CreateMetaFieldEncodings;
import dev.jvault.jira.egress.JiraFieldEncoding;
import dev.jvault.jira.gateway.JiraMetadataGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking Jira what shape a field's value takes.
 *
 * <p>The field key answers this for system fields and for nothing else, which is why a checkbox
 * group was being sent as a string and refused.
 */
class CreateMetaFieldEncodingsTest {

    private static final String PROJECT = "KAN";
    private static final String ISSUE_TYPE = "10001";

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-09-13T09:00:00Z"));

    private CreateMetaFieldEncodings encodings() {
        Clock moving = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        return new CreateMetaFieldEncodings(gateway(), moving, Duration.ofMinutes(5));
    }

    @Nested
    @DisplayName("what the key cannot say")
    class CustomFields {

        @Test
        @DisplayName("a checkbox group is an array of options, which its key never suggested")
        void checkboxGroupIsAnOptionArray() {
            assertThat(encodings().encodingFor(PROJECT, ISSUE_TYPE, "customfield_10021"))
                    .isEqualTo(JiraFieldEncoding.ID_OBJECT_ARRAY);
        }

        @Test
        @DisplayName("a system field answers the same with metadata as without")
        void systemFieldsAgree() {
            assertThat(encodings().encodingFor(PROJECT, ISSUE_TYPE, "priority"))
                    .isEqualTo(JiraFieldEncoding.ID_OBJECT)
                    .isEqualTo(JiraFieldEncoding.forField(null, null, null, "priority"));
        }

        @Test
        @DisplayName("a field this project cannot set falls back to what its key implies")
        void unknownFieldFallsBack() {
            assertThat(encodings().encodingFor(PROJECT, ISSUE_TYPE, "summary"))
                    .isEqualTo(JiraFieldEncoding.STRING);
        }
    }

    @Nested
    @DisplayName("the cost of asking")
    class Caching {

        @Test
        @DisplayName("one dispatch pass asks Jira once, however many fields it assembles")
        void metadataIsFetchedOncePerIssueType() {
            CreateMetaFieldEncodings resolver = encodings();

            resolver.encodingFor(PROJECT, ISSUE_TYPE, "customfield_10021");
            resolver.encodingFor(PROJECT, ISSUE_TYPE, "priority");
            resolver.encodingFor(PROJECT, ISSUE_TYPE, "description");

            assertThat(calls).hasValue(1);
        }

        @Test
        @DisplayName("a field an administrator retyped is picked up without a restart")
        void cacheExpires() {
            CreateMetaFieldEncodings resolver = encodings();
            resolver.encodingFor(PROJECT, ISSUE_TYPE, "priority");

            now.set(now.get().plus(Duration.ofMinutes(6)));
            resolver.encodingFor(PROJECT, ISSUE_TYPE, "priority");

            assertThat(calls).hasValue(2);
        }
    }

    @Nested
    @DisplayName("when Jira cannot be asked")
    class Unreachable {

        @Test
        @DisplayName("assembly continues on what the key implies rather than failing here")
        void fallsBackToTheKey() {
            failure.set(new IllegalStateException("jira unreachable"));

            // The pass this belongs to is about to fail at the write and retry. Refusing to
            // assemble would turn a transient outage into a stuck row for no gain.
            assertThat(encodings().encodingFor(PROJECT, ISSUE_TYPE, "priority"))
                    .isEqualTo(JiraFieldEncoding.ID_OBJECT);
        }

        @Test
        @DisplayName("an answer already known is preferred to no answer")
        void servesStaleRatherThanNothing() {
            CreateMetaFieldEncodings resolver = encodings();
            resolver.encodingFor(PROJECT, ISSUE_TYPE, "customfield_10021");

            failure.set(new IllegalStateException("jira unreachable"));
            now.set(now.get().plus(Duration.ofMinutes(6)));

            assertThat(resolver.encodingFor(PROJECT, ISSUE_TYPE, "customfield_10021"))
                    .isEqualTo(JiraFieldEncoding.ID_OBJECT_ARRAY);
        }
    }

    /** Shaped after what a real team-managed project returns for these fields. */
    private JiraMetadataGateway gateway() {
        return new JiraMetadataGateway() {
            @Override
            public List<FieldMeta> fields(String projectKey, String issueTypeId) {
                calls.incrementAndGet();
                RuntimeException thrown = failure.get();
                if (thrown != null) {
                    throw thrown;
                }
                return List.of(
                        new FieldMeta("customfield_10021", "Flagged", false, "array", "option",
                                "multicheckboxes", List.of(), false, List.of("set")),
                        new FieldMeta("priority", "Priority", false, "priority", null, null,
                                List.of(), false, List.of("set")),
                        new FieldMeta("description", "Description", false, "string", null, null,
                                List.of(), false, List.of("set")));
            }

            @Override
            public List<Project> projects() {
                return List.of();
            }

            @Override
            public List<IssueType> issueTypes(String projectKey) {
                return List.of();
            }

            @Override
            public CurrentUser currentUser() {
                return new CurrentUser("712020:abc", "Service Account", "en_GB");
            }

            @Override
            public List<UserRef> searchUsers(String projectKey, String query, boolean assignable) {
                return List.of();
            }

            @Override
            public List<IssueRef> searchIssues(String projectKey, String query, String childType) {
                return List.of();
            }

            @Override
            public Map<String, String> displayNamesOf(Collection<String> accountIds) {
                return Map.of();
            }
        };
    }
}
