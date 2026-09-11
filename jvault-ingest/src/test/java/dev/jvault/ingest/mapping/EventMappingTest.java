package dev.jvault.ingest.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.jvault.ingest.support.Mappings;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mapping layer: a restricted path language and a closed set of value sources, deliberately
 * short of being a scripting engine.
 */
class EventMappingTest {

    private static final String CANARY =
            "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private static final String EVENT = """
            {
              "alert": {
                "id": "alert-8f21c",
                "revision": 1,
                "severity": "HIGH",
                "title": "Unexpected outbound connection",
                "narrative": "%s",
                "tags": ["egress", "ci"],
                "evidence": [
                  {"filename": "capture.pcap", "size": 4096},
                  {"filename": "netflow.csv", "size": 512}
                ],
                "resolved": null
              },
              "reporter": {"email": "soc@example.com"}
            }
            """.formatted(CANARY);

    private final JsonNode event = read(EVENT);

    @Nested
    @DisplayName("expressions")
    class Expressions {

        @Test
        @DisplayName("dotted paths, array indices and wildcards resolve")
        void resolvesPaths() {
            assertThat(Expression.parse("$.alert.id").text(event)).contains("alert-8f21c");
            assertThat(Expression.parse("$.alert.evidence[0].filename").text(event))
                    .contains("capture.pcap");
            assertThat(Expression.parse("$.alert.evidence[*].filename").evaluateAll(event))
                    .hasSize(2);
        }

        @Test
        @DisplayName("a missing path yields nothing rather than throwing")
        void missingPathIsEmpty() {
            assertThat(Expression.parse("$.alert.nope").text(event)).isEmpty();
            assertThat(Expression.parse("$.alert.evidence[9].filename").text(event)).isEmpty();
        }

        @Test
        @DisplayName("an explicit JSON null counts as absent, not as the text \"null\"")
        void jsonNullIsAbsent() {
            // Otherwise a null field would populate Jira with the word "null", which looks like
            // data and is not.
            assertThat(Expression.parse("$.alert.resolved").text(event)).isEmpty();
        }

        @Test
        @DisplayName("numbers and booleans come through as text")
        void scalarsBecomeText() {
            assertThat(Expression.parse("$.alert.revision").text(event)).contains("1");
        }

        @ParameterizedTest
        @ValueSource(strings = {"alert.id", "$.", "$.alert[", "$.alert[x]", "$.alert[-1]", "$!x"})
        @DisplayName("a malformed expression is rejected when configured, not when an event arrives")
        void malformedExpressionsRejectedAtParseTime(String bad) {
            assertThatThrownBy(() -> Expression.parse(bad))
                    .isInstanceOf(MappingConfigurationException.class);
        }
    }

    @Nested
    @DisplayName("field sources")
    class Sources {

        @Test
        @DisplayName("literal, path, concat and lookup cover the shapes a mapping needs")
        void theFourSources() {
            assertThat(new FieldSource.Literal("ci").resolve(event)).contains("ci");
            assertThat(FieldSource.Path.of("$.alert.title").resolve(event))
                    .contains("Unexpected outbound connection");

            var summary = FieldSource.Concat.of(
                    new FieldSource.Literal("["),
                    FieldSource.Path.of("$.alert.severity"),
                    new FieldSource.Literal("] "),
                    FieldSource.Path.of("$.alert.title"));
            assertThat(summary.resolve(event))
                    .contains("[HIGH] Unexpected outbound connection");

            var priority = new FieldSource.Lookup(Expression.parse("$.alert.severity"),
                    Map.of("CRITICAL", "1", "HIGH", "2"), "3");
            assertThat(priority.resolve(event)).contains("2");
        }

        @Test
        @DisplayName("a lookup falls back rather than producing an id Jira does not have")
        void lookupFallsBack() {
            var priority = new FieldSource.Lookup(Expression.parse("$.alert.severity"),
                    Map.of("CRITICAL", "1"), "3");

            assertThat(priority.resolve(event)).contains("3");
        }

        @Test
        @DisplayName("a concat skips parts that resolve to nothing")
        void concatSkipsMissingParts() {
            var withMissing = FieldSource.Concat.of(
                    FieldSource.Path.of("$.alert.nope"),
                    new FieldSource.Literal("fallback title"));

            assertThat(withMissing.resolve(event)).contains("fallback title");
        }
    }

    @Nested
    @DisplayName("mapping validation")
    class Validation {

        @Test
        @DisplayName("a mapping without a dedupe key is refused")
        void dedupeKeyIsMandatory() {
            assertThatThrownBy(() -> EventMapping.builder("m", "t", "SEC", "10004")
                    .field("summary", new FieldSource.Literal("x"))
                    .build())
                    .isInstanceOf(MappingConfigurationException.class)
                    .hasMessageContaining("would create a second ticket");
        }

        @Test
        @DisplayName("a mapping without a summary is refused, because Jira requires one")
        void summaryIsMandatory() {
            assertThatThrownBy(() -> EventMapping.builder("m", "t", "SEC", "10004")
                    .dedupeKey("$.alert.id")
                    .field("description", new FieldSource.Literal("x"))
                    .build())
                    .isInstanceOf(MappingConfigurationException.class)
                    .hasMessageContaining("requires a summary");
        }
    }

    @Nested
    @DisplayName("mapping an event")
    class Mapping {

        @Test
        @DisplayName("an event becomes the same command the UI and REST API build")
        void producesATicketCommand() {
            EventMapper.Mapped mapped = new EventMapper(Mappings.incident()).map(event, "corr-1");

            assertThat(mapped.command().projectKey()).isEqualTo("SEC");
            assertThat(mapped.command().fields())
                    .containsEntry("summary", "[HIGH] Unexpected outbound connection")
                    .containsEntry("customfield_10010", "alert-8f21c")
                    .containsEntry("description", CANARY);
            assertThat(mapped.dedupeKey()).isEqualTo("alert-8f21c|1");
            assertThat(mapped.actor()).isEqualTo("soc@example.com");
        }

        @Test
        @DisplayName("Kafka-originated work runs as the integration identity")
        void identityIsTheIntegrationAccount() {
            EventMapper.Mapped mapped = new EventMapper(Mappings.incident()).map(event, "corr-1");

            // The event created the ticket, not a person; the actor is recorded separately.
            assertThat(mapped.command().origin().identityRef()).startsWith("INTEGRATION:");
            assertThat(mapped.command().origin().channel())
                    .isEqualTo(dev.jvault.content.TicketCommand.Origin.Channel.KAFKA);
            assertThat(mapped.command().origin().actorId()).isEqualTo("soc@example.com");
        }

        @Test
        @DisplayName("a mapping may tighten placement, and the policy engine still has the last word")
        void mappingCanRequestPlacement() {
            EventMapper.Mapped mapped = new EventMapper(Mappings.incident()).map(event, "corr-1");

            assertThat(mapped.command().overrides())
                    .containsEntry("description", dev.jvault.domain.placement.Placement.EXTERNAL);
        }

        @Test
        @DisplayName("an over-long summary is truncated to what Jira accepts")
        void summaryIsTruncated() {
            var mapping = EventMapping.builder("m", "t", "SEC", "10004")
                    .dedupeKey("$.alert.id")
                    .field(new EventMapping.FieldRule("summary",
                            FieldSource.Path.of("$.alert.narrative"), true, 40,
                            EventMapping.OverflowPolicy.TRUNCATE_ELLIPSIS, null))
                    .build();

            String summary = new EventMapper(mapping).map(event, "c").command()
                    .fields().get("summary");

            assertThat(summary).hasSize(40).endsWith("…");
        }

        @Test
        @DisplayName("a field that must not be truncated rejects the message instead")
        void rejectOnOverflow() {
            var mapping = EventMapping.builder("m", "t", "SEC", "10004")
                    .dedupeKey("$.alert.id")
                    .field("summary", new FieldSource.Literal("ok"))
                    .field(new EventMapping.FieldRule("customfield_1",
                            FieldSource.Path.of("$.alert.narrative"), false, 10,
                            EventMapping.OverflowPolicy.REJECT, null))
                    .build();

            assertThatThrownBy(() -> new EventMapper(mapping).map(event, "c"))
                    .isInstanceOf(EventMapper.EventValidationException.class);
        }
    }

    @Nested
    @DisplayName("validation failures carry no event data")
    class FailuresCarryNoData {

        @Test
        @DisplayName("a missing required path names the path and the violation, not the event")
        void missingRequiredPath() {
            var mapping = EventMapping.builder("m", "t", "SEC", "10004")
                    .dedupeKey("$.alert.id")
                    .require("$.alert.mandatoryThing")
                    .field("summary", new FieldSource.Literal("ok"))
                    .build();

            assertThatThrownBy(() -> new EventMapper(mapping).map(event, "c"))
                    .isInstanceOfSatisfying(EventMapper.EventValidationException.class, e -> {
                        assertThat(e.problems())
                                .extracting(EventMapper.FieldProblem::code)
                                .contains("REQUIRED_PATH_MISSING");
                        // The offending event may well carry a credential two fields away from
                        // the one that failed.
                        assertThat(e.getMessage()).doesNotContain(CANARY);
                        assertThat(e.problems().toString()).doesNotContain(CANARY);
                    });
        }

        @Test
        @DisplayName("every problem is reported at once, so a producer fixes them in one pass")
        void allProblemsReported() {
            var mapping = EventMapping.builder("m", "t", "SEC", "10004")
                    .dedupeKey("$.alert.id")
                    .require("$.a", "$.b", "$.c")
                    .field("summary", new FieldSource.Literal("ok"))
                    .build();

            assertThatThrownBy(() -> new EventMapper(mapping).map(event, "c"))
                    .isInstanceOfSatisfying(EventMapper.EventValidationException.class,
                            e -> assertThat(e.problems()).hasSize(3));
        }

        @Test
        @DisplayName("an incomplete dedupe key is refused rather than processed partially")
        void incompleteDedupeKeyIsRefused() {
            var mapping = EventMapping.builder("m", "t", "SEC", "10004")
                    .dedupeKey("$.alert.id", "$.alert.missingPart")
                    .field("summary", new FieldSource.Literal("ok"))
                    .build();

            // A partial key could not recognise a replay, which is the one thing the key is for.
            assertThatThrownBy(() -> new EventMapper(mapping).map(event, "c"))
                    .isInstanceOfSatisfying(EventMapper.EventValidationException.class,
                            e -> assertThat(e.problems())
                                    .extracting(EventMapper.FieldProblem::code)
                                    .contains("DEDUPE_KEY_PATH_MISSING"));
        }
    }

    private static JsonNode read(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
