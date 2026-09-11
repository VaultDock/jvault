package dev.jvault.domain.placement;

/** Where the link to external content is attached in Jira. */
public enum LinkPlacement {

    /** Inline in the field's own text, alongside the surrogate. */
    DESCRIPTION_PLACEHOLDER,

    /** As a Jira comment. Required for externally-placed comments, which need a Jira shell. */
    COMMENT,

    /**
     * As a Jira remote issue link with {@code globalId = jvault:content:{contentRef}}.
     * Idempotent by construction: Jira upserts on globalId (docs/00-verified-capabilities.md 0.6).
     */
    REMOTE_LINK
}
