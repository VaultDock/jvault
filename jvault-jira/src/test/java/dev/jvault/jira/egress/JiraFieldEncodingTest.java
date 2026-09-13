package dev.jvault.jira.egress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One table, asked two different ways.
 *
 * <p>The form asks with a schema in hand, because create metadata told it one. A caller with
 * only a field key gets the same answer for every system field, and when it did not, the symptom
 * was a form that looked right and a create Jira refused.
 *
 * <p>For a custom field the key answers nothing — {@code customfield_10021} is a checkbox group
 * on one deployment and a date on another — which is why the assembler asks Jira rather than the
 * key (see {@code JiraFieldEncodings}). What the key path still owes is that it never contradicts
 * the schema path where it does have an answer.
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
        JiraFieldEncoding withSchema = JiraFieldEncoding.forField(schemaType, null, null, fieldKey);
        JiraFieldEncoding keyOnly = JiraFieldEncoding.forField(null, null, null, fieldKey);

        assertThat(withSchema).isEqualTo(JiraFieldEncoding.valueOf(expected));
        assertThat(keyOnly)
                .as("the assembler only has the key, and its answer is the one Jira receives")
                .isEqualTo(withSchema);
    }

    @ParameterizedTest(name = "an array of {0} is {1}")
    @CsvSource({
            "string,    STRING_ARRAY",
            "option,    ID_OBJECT_ARRAY",
            "component, ID_OBJECT_ARRAY",
            "version,   ID_OBJECT_ARRAY",
            "group,     ID_OBJECT_ARRAY",
            "user,      ACCOUNT_OBJECT_ARRAY",
    })
    @DisplayName("what an array holds decides its shape")
    void arrayShapeFollowsItsItems(String items, String expected) {
        // Both are "array" to Jira's schema type. Sent as the wrong one, the field is rejected:
        // an array of strings where objects were wanted, or objects where strings were.
        assertThat(JiraFieldEncoding.forField("array", items, null, "customfield_10021"))
                .isEqualTo(JiraFieldEncoding.valueOf(expected));
    }

    @Test
    @DisplayName("an array whose items Jira does not name is an array of strings")
    void arrayWithoutItemsIsStrings() {
        // Which is what "array" meant before the item type could be asked for, so no deployment
        // that worked before this stops working because of it.
        assertThat(JiraFieldEncoding.forField("array", null, null, "customfield_10021"))
                .isEqualTo(JiraFieldEncoding.STRING_ARRAY);
    }

    @Test
    @DisplayName("a checkbox group is an array of options, whatever its key")
    void multiCheckboxesAreOptions() {
        // The bug this table was extended for: Jira wants [{"id": "10019"}] and was sent
        // "10019", which it refused for not being an array at all.
        assertThat(JiraFieldEncoding.forField("array", "option", "multicheckboxes",
                "customfield_10021")).isEqualTo(JiraFieldEncoding.ID_OBJECT_ARRAY);
    }

    @Test
    @DisplayName("an unrecognised field is a string, which earns a named error rather than a guess")
    void unknownFieldsAreStrings() {
        assertThat(JiraFieldEncoding.forField(null, null, null, "customfield_99999"))
                .isEqualTo(JiraFieldEncoding.STRING);
        // A wrong string produces a field-level error from Jira naming the field. A guessed
        // object shape produces a rejection that names nothing useful.
        assertThat(JiraFieldEncoding.forField("any", null, "some-app-field", "customfield_99999"))
                .isEqualTo(JiraFieldEncoding.STRING);
    }
}
