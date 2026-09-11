package dev.jvault.ingest.support;

import dev.jvault.domain.placement.Placement;
import dev.jvault.ingest.mapping.EventMapping;
import dev.jvault.ingest.mapping.FieldSource;

/** Shared mapping fixtures, so the mapping tests and the end-to-end test exercise the same one. */
public final class Mappings {

    private Mappings() {
    }

    /** The worked example from docs/examples/kafka-mappings.yaml. */
    public static EventMapping incident() {
        return EventMapping.builder("sec-incident-v2", "security.alerts.v1", "SEC", "10004")
                .deployment("jira-cloud-prod")
                .dedupeKey("$.alert.id", "$.alert.revision")
                .require("$.alert.id", "$.alert.severity", "$.alert.title")
                .actor("$.reporter.email", EventMapping.ActorMode.RECORD_ONLY)
                .field(new EventMapping.FieldRule("summary",
                        FieldSource.Concat.of(
                                new FieldSource.Literal("["),
                                FieldSource.Path.of("$.alert.severity"),
                                new FieldSource.Literal("] "),
                                FieldSource.Path.of("$.alert.title")),
                        true, 255, EventMapping.OverflowPolicy.TRUNCATE_ELLIPSIS, null))
                .field(new EventMapping.FieldRule("description",
                        FieldSource.Path.of("$.alert.narrative"), true, null, null,
                        Placement.EXTERNAL))
                .field("customfield_10010", FieldSource.Path.of("$.alert.id"))
                .build();
    }
}
