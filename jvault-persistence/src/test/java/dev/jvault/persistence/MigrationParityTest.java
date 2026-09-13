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
    private static final String OUTBOX = "V1__outbox.sql";
    private static final String VAULT = "V2__vault.sql";

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
    @DisplayName("the unverified engines say so in every script")
    void unverifiedScriptsAreLabelled() {
        for (String script : List.of(OUTBOX, VAULT)) {
            assertThat(scriptOf("sqlserver", script)).contains("UNVERIFIED");
            assertThat(scriptOf("oracle", script)).contains("UNVERIFIED");
            assertThat(scriptOf("postgresql", script)).doesNotContain("UNVERIFIED");
        }
    }

    @Test
    @DisplayName("every engine's vault tables declare the same columns")
    void vaultColumnsMatchAcrossEngines() {
        // V1 is not the only script any more, and a second one that nobody compares is exactly
        // how the three engines start meaning different things.
        Set<String> reference = columnsOf("postgresql", VAULT);
        assertThat(reference)
                .as("the reference script should define the tables we think it does")
                .contains("ticket_ref", "content_ref", "version_id", "wrapped_dek",
                        "display_name_enc", "grant_id", "permission");

        for (String engine : ENGINES) {
            assertThat(columnsOf(engine, VAULT))
                    .as("%s/%s has drifted from postgresql/%s", engine, VAULT, VAULT)
                    .containsExactlyInAnyOrderElementsOf(reference);
        }
    }

    @Test
    @DisplayName("every engine constrains the dedupe key that stops a replay duplicating")
    void dedupeConstraintPresentEverywhere() {
        for (String engine : ENGINES) {
            assertThat(scriptOf(engine, VAULT).toLowerCase(Locale.ROOT))
                    .as("%s is missing the dedupe uniqueness that stops a replayed message "
                            + "creating a second Jira issue", engine)
                    .contains("uq_ticket_dedupe");
        }
    }

    @Test
    @DisplayName("no engine stores a display name in the clear")
    void displayNamesAreEncryptedEverywhere() {
        for (String engine : ENGINES) {
            String script = scriptOf(engine, VAULT);
            assertThat(script)
                    .as("%s stores a display name, which must be encrypted: a filename is "
                            + "frequently the most sensitive thing about a file", engine)
                    .contains("display_name_enc")
                    .doesNotContain("display_name  ");
        }
    }

    private static Set<String> columnsOf(String engine) {
        return columnsOf(engine, OUTBOX);
    }

    private static Set<String> columnsOf(String engine, String script) {
        var columns = new LinkedHashSet<String>();
        Matcher matcher = COLUMN.matcher(scriptOf(engine, script));
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        // CONSTRAINT lines start with four spaces and an uppercase word too; drop them.
        columns.removeAll(Arrays.asList("constraint"));
        return columns;
    }

    private static String scriptOf(String engine) {
        return scriptOf(engine, OUTBOX);
    }

    private static String scriptOf(String engine, String script) {
        String path = "/db/migration/" + engine + "/" + script;
        try (InputStream in = MigrationParityTest.class.getResourceAsStream(path)) {
            assertThat(in).as("missing migration %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
