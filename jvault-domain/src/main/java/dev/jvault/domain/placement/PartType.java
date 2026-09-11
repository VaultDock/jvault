package dev.jvault.domain.placement;

/** The addressable pieces of a ticket that a placement policy can target. */
public enum PartType {
    SUMMARY,
    DESCRIPTION,
    BODY,
    COMMENT,
    ATTACHMENT,
    CUSTOM_FIELD
}
