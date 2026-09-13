package dev.jvault.domain.placement;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The complete set of substitutions a surrogate template may use.
 *
 * <p>This enum <em>is</em> the safety property. There is no token that interpolates the original
 * value, and {@link SurrogateRenderer} has no expression evaluation — only substitution from this
 * list. A mis-authored template therefore cannot leak content; the worst it can do is fail
 * validation (docs/01-requirements.md FR-CP-3 AC2).
 */
public enum SurrogateToken {

    LINK("link"),
    /** The ticket view: everything secured on this ticket, rather than one part of it. */
    TICKET_LINK("ticketLink"),
    TICKET_REF("ticketRef"),
    TICKET_REF_SHORT("ticketRefShort"),
    CONTENT_REF("contentRef"),
    CLASSIFICATION("classification"),
    PART_TYPE("partType"),
    ISSUE_TYPE("issueType"),
    PROJECT("project"),
    CREATED_AT("createdAt"),
    ACTOR_DISPLAY_NAME("actorDisplayName"),
    SIZE_HUMAN("sizeHuman"),
    MEDIA_TYPE("mediaType"),
    VERSION_NO("versionNo");

    private static final Map<String, SurrogateToken> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(SurrogateToken::templateName, Function.identity()));

    private final String templateName;

    SurrogateToken(String templateName) {
        this.templateName = templateName;
    }

    public String templateName() {
        return templateName;
    }

    public static Optional<SurrogateToken> byTemplateName(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    public static String knownTokens() {
        return Arrays.stream(values())
                .map(SurrogateToken::templateName)
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
