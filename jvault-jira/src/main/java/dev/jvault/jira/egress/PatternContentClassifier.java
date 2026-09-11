package dev.jvault.jira.egress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based classifier with a small default pack of high-confidence secret shapes.
 *
 * <p>The pack is deliberately conservative. A classifier that fires often gets disabled, and a
 * disabled classifier protects nothing — so these patterns target shapes that are almost never
 * anything but a credential. Broader, noisier packs belong in the advisory tier, where a
 * detection annotates rather than blocks (docs/05-content-placement.md 5.6).
 *
 * <p>Additional patterns are supplied by configuration; this class only provides the defaults.
 */
public final class PatternContentClassifier implements ContentClassifier {

    private final Map<String, Pattern> patterns;

    public PatternContentClassifier(Map<String, Pattern> patterns) {
        this.patterns = Map.copyOf(patterns);
    }

    public static PatternContentClassifier withDefaults() {
        var p = new LinkedHashMap<String, Pattern>();

        // PEM-encoded private key of any flavour. Effectively zero false positives.
        p.put("PRIVATE_KEY_BLOCK",
                Pattern.compile("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----"));

        // AWS long-lived access key id: fixed prefix plus 16 base-32 characters.
        p.put("AWS_ACCESS_KEY_ID",
                Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"));

        // A secret-looking assignment: an identifier whose name says "secret" with a long
        // opaque value. Note the hand-rolled boundaries: \b does not work here, because the
        // most common real shape is an environment variable such as AWS_SECRET_ACCESS_KEY,
        // where the keyword is surrounded by underscores and \b therefore never fires.
        p.put("SECRET_ASSIGNMENT",
                Pattern.compile("(?i)(?<![A-Za-z0-9])"
                        + "(?:[A-Za-z0-9]+[_-])*"                        // AWS_ , MY_APP_
                        + "(?:secret|password|passwd|api[_-]?key|token)"
                        + "(?:[_-][A-Za-z0-9]+)*"                        // _ACCESS_KEY
                        + "\\s*[:=]\\s*[\"']?[A-Za-z0-9/+=_\\-]{16,}"));

        // JSON Web Token: three base64url segments, and the header almost always starts eyJ.
        p.put("JWT",
                Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"));

        // Atlassian API token / PAT shapes, which are exactly what this system handles.
        p.put("ATLASSIAN_TOKEN",
                Pattern.compile("\\bATATT3[A-Za-z0-9_\\-=]{20,}"));

        return new PatternContentClassifier(p);
    }

    @Override
    public List<Detection> detect(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var detections = new ArrayList<Detection>();
        patterns.forEach((code, pattern) -> {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                detections.add(new Detection(code, m.start(), m.end() - m.start()));
            }
        });
        return List.copyOf(detections);
    }
}
