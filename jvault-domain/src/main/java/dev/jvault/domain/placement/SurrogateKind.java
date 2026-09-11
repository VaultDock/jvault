package dev.jvault.domain.placement;

/** How the value written into Jira in place of external content is produced. */
public enum SurrogateKind {

    /** Fixed text from an allow-listed template. Reveals nothing about the content. */
    PLACEHOLDER,

    /** Like {@link #PLACEHOLDER} but worded for content that was withheld rather than moved. */
    REDACTED,

    /**
     * Text derived from the content itself. Available only where a policy explicitly enables it
     * <em>and</em> a classifier passes the result — see docs/05-content-placement.md 5.3. Not
     * implemented yet; policies requesting it are rejected at validation time.
     */
    DERIVED_SUMMARY
}
