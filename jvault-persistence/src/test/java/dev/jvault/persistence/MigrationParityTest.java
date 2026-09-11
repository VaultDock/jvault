package dev.jvault.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One logical schema, three physical scripts — and nothing keeps them in step except this.
 *
 * <p>Decision D5 promised per-vendor migrations "with a test asserting they stay in step". A
 * column added to PostgreSQL and forgotten in Oracle would not fail anything until someone
 * deployed on Oracle, by which point the omission is a production incident rather than a
 * red build.
 */
class MigrationParityTest {

    private static final List<String> ENGINES = List.of("postgresql", "sqlserver", "oracle");

    private static final Pattern COLUMN = Pattern.compile(
            "^\\s{4}([a-z_]+)\\s+[A-Z]", Pattern.MULTILINE);

    @Test
    @DisplayName("every engine's outbox table declares the same columns")
    void columnsMatchAcrossEngines() {
        Set<String> reference = columnsOf("postgresql");

        assertThat(reference)
                .as("the reference script should define the table we think it does")
                .contains("id", "ticket_ref", "issue_lane", "operation", "effect_key",
                        "payload_ref", "state", "attempts", "next_attempt_at", "created_at");

        for (String engine : ENGINES) {
            assertThat(columnsOf(engine))
                    .as("%s/V1__outbox.sql has drifted from postgresql/V1__outbox.sql", engine)
                    .containsExactlyInAnyOrderElementsOf(reference);
        }
    }

    @Test
    @DisplayName("every engine declares the unique constraint that makes append idempotent")
    void uniqueConstraintPresentEverywhere() {
        for (String engine : ENGINES) {
            assertThat(scriptOf(engine).toLowerCase(Locale.ROOT))
                    .as("%s is missing the (ticket_ref, effect_key) unique constraint, which is "
                            + "the only thing preventing a replayed effect from being dispatched "
                            + "twice", engine)
                    .contains("unique (ticket_ref, effect_key)");
        }
    }

    @Test
    @DisplayName("every engine indexes the claim query and the lane grouping")
    void indexesPresentEverywhere() {
        for (String engine : ENGINES) {
            String script = scriptOf(engine).toLowerCase(Locale.ROOT);
            assertThat(script).as("%s claim index", engine).contains("ix_jira_outbox_due");
            assertThat(script).as("%s lane index", engine).contains("ix_jira_outbox_lane");
        }
    }

    @Test
    @DisplayName("the unverified engines say so in the script itself")
    void unverifiedScriptsAreLabelled() {
        assertThat(scriptOf("sqlserver")).contains("UNVERIFIED");
        assertThat(scriptOf("oracle")).contains("UNVERIFIED");
        assertThat(scriptOf("postgresql")).doesNotContain("UNVERIFIED");
    }

    private static Set<String> columnsOf(String engine) {
        var columns = new LinkedHashSet<String>();
        Matcher matcher = COLUMN.matcher(scriptOf(engine));
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        // CONSTRAINT lines start with four spaces and an uppercase word too; drop them.
        columns.removeAll(Arrays.asList("constraint"));
        return columns;
    }

    private static String scriptOf(String engine) {
        String path = "/db/migration/" + engine + "/V1__outbox.sql";
        try (InputStream in = MigrationParityTest.class.getResourceAsStream(path)) {
            assertThat(in).as("missing migration %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
