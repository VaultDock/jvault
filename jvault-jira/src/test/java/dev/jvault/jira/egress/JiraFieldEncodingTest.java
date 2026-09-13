package dev.jvault.jira.egress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One table, asked two different ways.
 *
 * <p>The form asks with a schema in hand, because create metadata told it one. The payload
 * assembler asks with only a field key, because a ticket sitting in the outbox has to dispatch
 * whether or not Jira's metadata endpoint is reachable.
 *
 * <p>Those two answers must agree for every system field, and when they did not, the symptom was
 * a form that looked right and a create Jira refused. The key-only path is the one that reaches
 * Jira, so it is the one that has to know.
 */
class JiraFieldEncodingTest {

    @ParameterizedTest(name = "{0} is {2} whether or not the schema is known")
    @CsvSource({
            "parent,     issuelink,  KEY_OBJECT",
            "project,    project,    KEY_OBJECT",
            "assignee,   user,       ACCOUNT_OBJECT",
            "reporter,   user,       ACCOUNT_OBJECT",
            "priority,   priority,   ID_OBJECT",
            "issuetype,  issuetype,  ID_OBJECT",
            "labels,     array,      STRING_ARRAY",
            "description,string,     RICH_TEXT",
            "environment,string,     RICH_TEXT",
            "summary,    string,     STRING",
            "duedate,    date,       STRING",
    })
    @DisplayName("both paths agree")
    void bothPathsAgree(String fieldKey, String schemaType, String expected) {
        JiraFieldEncoding withSchema = JiraFieldEncoding.forField(schemaType, null, fieldKey);
        JiraFieldEncoding keyOnly = JiraFieldEncoding.forField(null, null, fieldKey);

        assertThat(withSchema).isEqualTo(JiraFieldEncoding.valueOf(expected));
        assertThat(keyOnly)
                .as("the assembler only has the key, and its answer is the one Jira receives")
                .isEqualTo(withSchema);
    }

    @Test
    @DisplayName("an unrecognised field is a string, which earns a named error rather than a guess")
    void unknownFieldsAreStrings() {
        assertThat(JiraFieldEncoding.forField(null, null, "customfield_99999"))
                .isEqualTo(JiraFieldEncoding.STRING);
        // A wrong string produces a field-level error from Jira naming the field. A guessed
        // object shape produces a rejection that names nothing useful.
        assertThat(JiraFieldEncoding.forField("any", "some-app-field", "customfield_99999"))
                .isEqualTo(JiraFieldEncoding.STRING);
    }
}
