package dev.jvault.ingest.mapping;

import dev.jvault.domain.placement.Placement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventMappingLoaderTest {

    private static final String MINIMAL = """
            mappings:
              - id: sec-incident-v2
                topic: security.alerts.v1
                consumerGroup: jvault-sec
                concurrency: 3
                deployment: jira-cloud-prod
                target: { project: SEC, issueType: "10004" }
                idempotency:
                  dedupeKey: ["$.alert.id", "$.alert.revision"]
                guards:
                  rejectIfMissing: ["$.alert.id", "$.alert.severity"]
                fields:
                  summary:
                    expr: "concat('[', $.alert.severity, '] ', $.alert.title)"
                    maxLength: 255
                    onOverflow: TRUNCATE_ELLIPSIS
                  description:
                    expr: "$.alert.narrative"
                    placement: EXTERNAL
                  priority:
                    lookup:
                      from: "$.alert.severity"
                      table: { CRITICAL: "1", HIGH: "2" }
                      default: "3"
                  labels:
                    literal: ["ci", "auto-raised"]
            """;

    @Test
    @DisplayName("a mapping loads with its dedupe key, guards and field rules")
    void loadsAMapping() {
        EventMapping mapping = load(MINIMAL).get(0);

        assertThat(mapping.id()).isEqualTo("sec-incident-v2");
        assertThat(mapping.topic()).isEqualTo("security.alerts.v1");
        assertThat(mapping.projectKey()).isEqualTo("SEC");
        assertThat(mapping.dedupeKeyPaths()).hasSize(2);
        assertThat(mapping.requiredPaths()).hasSize(2);
        assertThat(mapping.fields()).extracting(EventMapping.FieldRule::fieldKey)
                .containsExactlyInAnyOrder("summary", "description", "priority", "labels");
    }

    @Test
    @DisplayName("a declared placement survives loading")
    void placementIsCarried() {
        EventMapping mapping = load(MINIMAL).get(0);

        // The one property that must never be quietly lost: it is the author saying this field's
        // value is not to be written into Jira.
        EventMapping.FieldRule description = mapping.fields().stream()
                .filter(rule -> rule.fieldKey().equals("description")).findFirst().orElseThrow();
        assertThat(description.requestedPlacement()).isEqualTo(Placement.EXTERNAL);
    }

    @Test
    @DisplayName("concat is parsed into its literal and path parts")
    void concatIsParsed() {
        EventMapping mapping = load(MINIMAL).get(0);

        EventMapping.FieldRule summary = mapping.fields().stream()
                .filter(rule -> rule.fieldKey().equals("summary")).findFirst().orElseThrow();
        assertThat(summary.source()).isInstanceOf(FieldSource.Concat.class);
        assertThat(((FieldSource.Concat) summary.source()).parts()).hasSize(4);
        assertThat(summary.maxLength()).isEqualTo(255);
    }

    @Test
    @DisplayName("a key this version does not implement stops the deployment")
    void unknownKeysAreRefused() {
        String withAttachments = MINIMAL + """
                    attachments:
                      - forEach: "$.alert.evidence[*]"
                        fileName: "$.filename"
                """;

        // The dangerous version of this is the silent one. A file declaring attachment handling
        // that nothing implements would look configured and do nothing, and a file declaring a
        // placement nobody reads would put content in Jira.
        assertThatThrownBy(() -> load(withAttachments))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("attachments")
                .hasMessageContaining("does not implement");
    }

    @Test
    @DisplayName("a field key nobody implements is refused too, not just a mapping key")
    void unknownFieldKeysAreRefused() {
        String withTransform = """
                mappings:
                  - id: sec
                    topic: t
                    target: { project: SEC, issueType: "1" }
                    fields:
                      labels:
                        expr: "$.alert.tags[*]"
                        transform: SLUGIFY
                """;

        // SLUGIFY is in the documented format and not implemented here. Accepting the field and
        // dropping the transform would send Jira values the author never intended to send.
        assertThatThrownBy(() -> load(withTransform))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("transform");
    }

    @Test
    @DisplayName("a file with no mappings is refused rather than starting an idle consumer")
    void emptyFilesAreRefused() {
        assertThatThrownBy(() -> load("mappings: []"))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("no mappings");
    }

    @Test
    @DisplayName("a mapping with no fields could not populate a ticket, and says so")
    void mappingsNeedFields() {
        assertThatThrownBy(() -> load("""
                mappings:
                  - id: empty
                    topic: t
                    target: { project: SEC, issueType: "1" }
                    fields: {}
                """))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("no fields");
    }

    @Test
    @DisplayName("consumer settings are read separately from what a message means")
    void consumerSettingsAreSeparate() {
        var consumers = EventMappingLoader.consumersIn(stream(MINIMAL));

        assertThat(consumers.get("sec-incident-v2").groupId()).isEqualTo("jvault-sec");
        assertThat(consumers.get("sec-incident-v2").concurrency()).isEqualTo(3);
    }

    private static List<EventMapping> load(String yaml) {
        return EventMappingLoader.load(stream(yaml));
    }

    private static ByteArrayInputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
