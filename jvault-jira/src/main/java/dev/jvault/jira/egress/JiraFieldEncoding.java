package dev.jvault.jira.egress;

/**
 * How one Jira field's string value becomes JSON.
 *
 * <p>Jira's create payload is not a flat map of strings: a project is {@code {"key": "SEC"}}, a
 * priority is {@code {"id": "2"}}, labels are an array, and a description is a rich-text document
 * whose shape depends on the API version. The caller knows which is which — ultimately from
 * create metadata — so the encoding travels with the payload rather than being guessed here.
 *
 * <p>Guessing would work until the first field whose value happens to look like an id.
 *
 * <p>It lives beside the payload rather than with the transport on purpose: it describes the
 * <em>shape of a value</em>, which is a property of the payload, and putting it in the gateway
 * package would have the egress boundary depending on the thing it guards. The architecture test
 * caught exactly that.
 */
public enum JiraFieldEncoding {

    /** A plain JSON string. The default. */
    STRING,

    /** Rich text: ADF on Cloud, wiki markup on Data Center. */
    RICH_TEXT,

    /** {@code {"id": value}} — priorities, issue types, most select fields. */
    ID_OBJECT,

    /** {@code {"key": value}} — projects. */
    KEY_OBJECT,

    /** {@code {"accountId": value}} — users on Cloud. */
    ACCOUNT_OBJECT,

    /** A comma-separated value becoming a JSON array of strings. Labels, components. */
    STRING_ARRAY,

    /** A number rather than a string. */
    NUMBER;

    /**
     * The encoding for one field, from what create metadata says about it.
     *
     * <p>One table, used by both the form that collects a value and the payload that sends it.
     * Two tables would agree until the first divergence, and the symptom would be a field the
     * user filled in correctly that Jira rejects.
     *
     * <p>Anything unrecognised is {@link #STRING}. That is not a guess dressed up as a default:
     * a wrong string produces a field-level error from Jira naming the field, whereas a guessed
     * object shape produces a rejection that names nothing useful.
     *
     * @param schemaType Jira's schema type, or {@code null} when it is not known
     * @param customType the custom field type's short name, or {@code null} for a system field
     * @param fieldKey   the field id, which for system fields is the system name
     */
    public static JiraFieldEncoding forField(String schemaType, String customType, String fieldKey) {
        if (customType != null) {
            JiraFieldEncoding byCustomType = switch (customType) {
                case "textarea" -> RICH_TEXT;
                case "float" -> NUMBER;
                case "labels" -> STRING_ARRAY;
                case "datepicker", "datetime", "textfield", "url", "readonlyfield" -> STRING;
                case "select", "radiobuttons", "cascadingselect" -> ID_OBJECT;
                case "userpicker" -> ACCOUNT_OBJECT;
                default -> null;
            };
            if (byCustomType != null) {
                return byCustomType;
            }
        }

        if (fieldKey != null) {
            JiraFieldEncoding byKey = switch (fieldKey) {
                case "description", "environment" -> RICH_TEXT;
                case "labels" -> STRING_ARRAY;
                case "priority", "issuetype", "resolution", "security" -> ID_OBJECT;
                // Field keys, not schema names: "issuelink" belongs in the switch below and was
                // briefly here, where nothing is called that — so `parent` matched neither table
                // and was sent to Jira as a bare string.
                case "project", "parent" -> KEY_OBJECT;
                case "assignee", "reporter" -> ACCOUNT_OBJECT;
                default -> null;
            };
            if (byKey != null) {
                return byKey;
            }
        }

        if (schemaType == null) {
            return STRING;
        }
        return switch (schemaType) {
            case "number" -> NUMBER;
            case "user" -> ACCOUNT_OBJECT;
            // A parent, an epic link, a blocked-by: Jira names the issue, not its id.
            case "project", "issuelink" -> KEY_OBJECT;
            case "priority", "issuetype", "resolution", "option", "securitylevel" -> ID_OBJECT;
            // An array of plain strings. An array of options would need a shape this enum does
            // not have; sending it as a string earns a named error rather than a silent mangling.
            case "array" -> STRING_ARRAY;
            default -> STRING;
        };
    }
}
