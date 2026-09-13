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
    NUMBER
}
