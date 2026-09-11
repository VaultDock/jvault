package dev.jvault.jira.egress;

import java.util.List;

/**
 * Detects sensitive material in text bound for Jira.
 *
 * <p>The second, independent layer of the egress check. Where {@link ContentHashIndex} catches
 * "this is the content we deliberately externalised", the classifier catches "this looks like a
 * secret regardless of where it came from" — a credential pasted into a description that no
 * policy externalised, for instance.
 *
 * <p>Detections report a <em>code and an offset</em>, never the matched text. A detector that
 * echoed what it found would reintroduce the leak into the very error path meant to prevent it.
 */
public interface ContentClassifier {

    List<Detection> detect(String text);

    /**
     * @param code   stable identifier of the detector that fired, safe for logs and metrics
     * @param offset character offset of the match, useful for a human reviewing the source
     * @param length length of the match
     */
    record Detection(String code, int offset, int length) {
    }

    /** A classifier that finds nothing. The default until patterns are configured. */
    static ContentClassifier none() {
        return text -> List.of();
    }
}
