package dev.jvault.domain.placement;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders surrogate templates by substitution from a fixed token set.
 *
 * <p>Deliberately not a template engine. There is no expression evaluation, no method calls, no
 * property navigation and no way to reach the original content: the only values available are
 * those the caller puts in the {@link EnumMap}, keyed by {@link SurrogateToken}. That is what
 * makes "a surrogate cannot leak content" a structural property rather than a review item.
 */
public final class SurrogateRenderer {

    /** Jira caps issue summaries at 255 characters. */
    public static final int SUMMARY_MAX_LENGTH = 255;

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*)\\s*}}");
    private static final String ELLIPSIS = "…";

    private SurrogateRenderer() {
    }

    /**
     * Token names used by the template that are not in {@link SurrogateToken}.
     * Used by policy validation so a typo is caught on save, not at ticket creation.
     */
    public static List<String> unknownTokens(String template) {
        var unknown = new ArrayList<String>();
        Matcher m = TOKEN.matcher(template);
        while (m.find()) {
            String name = m.group(1);
            if (SurrogateToken.byTemplateName(name).isEmpty()) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    /**
     * Substitutes known tokens. A token the caller supplied no value for renders as the empty
     * string rather than failing: a surrogate with a missing optional detail is still a usable
     * placeholder, whereas a failed render would block a Jira write.
     *
     * @throws IllegalArgumentException if the template uses a token outside the allow-list;
     *                                  policy validation should have caught this already
     */
    public static String render(SurrogateSpec spec, Map<SurrogateToken, String> values) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(values, "values");

        var unknown = unknownTokens(spec.template());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "surrogate template uses unknown tokens " + unknown
                            + "; known tokens are: " + SurrogateToken.knownTokens());
        }

        Matcher m = TOKEN.matcher(spec.template());
        var out = new StringBuilder();
        while (m.find()) {
            SurrogateToken token = SurrogateToken.byTemplateName(m.group(1)).orElseThrow();
            String value = values.getOrDefault(token, "");
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return collapseWhitespace(out.toString());
    }

    /**
     * Renders a summary surrogate, guaranteeing Jira will accept it: non-blank and within the
     * 255-character cap (docs/01-requirements.md FR-CP-3 AC1).
     */
    public static String renderSummary(SurrogateSpec spec, Map<SurrogateToken, String> values) {
        String rendered = render(spec, values);
        if (rendered.isBlank()) {
            // A blank summary would be rejected by Jira and would strand the ticket in the
            // outbox, so fall back to something Jira will accept.
            rendered = "[" + values.getOrDefault(SurrogateToken.CLASSIFICATION, "RESTRICTED")
                    + "] details in jvault";
        }
        return truncate(rendered, SUMMARY_MAX_LENGTH);
    }

    static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - ELLIPSIS.length()) + ELLIPSIS;
    }

    /** Empty substitutions leave double spaces behind; tidy them so surrogates read well. */
    private static String collapseWhitespace(String s) {
        return s.replaceAll("[ \\t]{2,}", " ").strip();
    }

    /** Convenience for building the value map. */
    public static Map<SurrogateToken, String> values() {
        return new EnumMap<>(SurrogateToken.class);
    }
}
