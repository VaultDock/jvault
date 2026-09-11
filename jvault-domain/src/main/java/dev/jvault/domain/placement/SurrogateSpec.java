package dev.jvault.domain.placement;

import java.util.Objects;

/**
 * The template used to produce Jira-visible text in place of external content.
 *
 * @param kind     how the surrogate is produced
 * @param template text containing only {@link SurrogateToken} substitutions in
 *                 <code>{{token}}</code> form
 */
public record SurrogateSpec(SurrogateKind kind, String template) {

    public SurrogateSpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(template, "template");
        if (template.isBlank()) {
            throw new IllegalArgumentException("surrogate template must not be blank");
        }
    }

    public static SurrogateSpec placeholder(String template) {
        return new SurrogateSpec(SurrogateKind.PLACEHOLDER, template);
    }

    /** The surrogate used when a policy externalises a field but names no template. */
    public static SurrogateSpec defaultFor(PartType partType) {
        return switch (partType) {
            case SUMMARY -> placeholder("[{{classification}}] {{issueType}} {{ticketRefShort}} — details in jvault");
            case COMMENT -> placeholder("Comment stored in jvault — {{link}}");
            case ATTACHMENT -> new SurrogateSpec(SurrogateKind.REDACTED,
                    "File held in jvault ({{sizeHuman}}, {{mediaType}}) — {{link}}");
            case DESCRIPTION, BODY, CUSTOM_FIELD -> placeholder(
                    "This content is stored in jvault and is not visible in Jira. "
                            + "Classification: {{classification}}. Open: {{link}}");
        };
    }
}
