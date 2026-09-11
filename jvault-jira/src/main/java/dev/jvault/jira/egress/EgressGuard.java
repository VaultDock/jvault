package dev.jvault.jira.egress;

import dev.jvault.domain.common.Classification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The only way to produce a {@link JiraSafePayload}.
 *
 * <p>Runs three independent checks over every Jira-bound string and fails closed on any of them:
 *
 * <ol>
 *   <li>The value does not contain the redaction marker — which would mean a
 *       {@link dev.jvault.domain.common.SensitiveValue} was stringified somewhere upstream.
 *       That is a bug rather than a leak, but it is a bug in exactly the code whose correctness
 *       the boundary depends on, so it is treated as a violation.</li>
 *   <li>The value does not match the ticket's externally-placed content
 *       ({@link ContentHashIndex}).</li>
 *   <li>At or above the configured classification threshold, the value trips no detector
 *       ({@link ContentClassifier}).</li>
 * </ol>
 *
 * <p>Below the threshold the classifier still runs, but advisory: detections are returned for
 * metrics and annotation without blocking. This matters operationally — a classifier that blocks
 * an incident ticket on a false positive is its own kind of outage, so blocking is reserved for
 * content the administrator has already declared sensitive.
 *
 * <p>Stateless and thread-safe.
 */
public final class EgressGuard {

    private static final String REDACTION_MARKER = "«redacted»";

    private final ContentClassifier classifier;
    private final Classification blockingThreshold;

    public EgressGuard(ContentClassifier classifier, Classification blockingThreshold) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.blockingThreshold = Objects.requireNonNull(blockingThreshold, "blockingThreshold");
    }

    /** Default posture: the pattern pack, blocking at RESTRICTED and above. */
    public static EgressGuard withDefaults() {
        return new EgressGuard(PatternContentClassifier.withDefaults(), Classification.RESTRICTED);
    }

    /**
     * @throws EgressViolationException if any check fails. Every violation found is reported, so
     *                                  an operator sees the whole picture rather than the first
     *                                  problem.
     */
    public JiraSafePayload sanitise(JiraWriteRequest request) {
        Objects.requireNonNull(request, "request");

        var violations = new ArrayList<EgressViolation>();
        var checks = new ArrayList<String>();

        ContentHashIndex index = ContentHashIndex.of(request.externalValues());
        boolean blocking = request.classification().atLeast(blockingThreshold);

        checks.add("redaction-marker");
        checks.add(index.isEmpty() ? "content-hash:skipped(no-external-parts)" : "content-hash");
        checks.add(blocking ? "classifier:blocking" : "classifier:advisory");

        var advisoryDetections = new LinkedHashMap<String, List<ContentClassifier.Detection>>();

        for (Map.Entry<String, String> entry : request.textFields().entrySet()) {
            String fieldKey = entry.getKey();
            String value = entry.getValue();
            if (value == null) {
                continue;
            }

            if (value.contains(REDACTION_MARKER)) {
                violations.add(new EgressViolation(fieldKey,
                        EgressViolation.SENSITIVE_VALUE_STRINGIFIED,
                        "a SensitiveValue reached the payload via toString()"));
                // Skip the remaining checks for this field: the value is already known bad, and
                // the marker itself would pollute the hash comparison.
                continue;
            }

            if (!index.isEmpty()) {
                ContentHashIndex.MatchKind match = index.match(value);
                if (match != null) {
                    violations.add(new EgressViolation(fieldKey,
                            EgressViolation.EXTERNAL_CONTENT_IN_PAYLOAD,
                            "match=" + match));
                    continue;
                }
            }

            List<ContentClassifier.Detection> detections = classifier.detect(value);
            if (!detections.isEmpty()) {
                if (blocking) {
                    for (ContentClassifier.Detection d : detections) {
                        violations.add(new EgressViolation(fieldKey,
                                EgressViolation.CLASSIFIED_CONTENT_DETECTED,
                                "detector=" + d.code() + ", offset=" + d.offset()));
                    }
                } else {
                    advisoryDetections.put(fieldKey, detections);
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new EgressViolationException(violations);
        }

        if (!advisoryDetections.isEmpty()) {
            checks.add("classifier:advisory-detections=" + advisoryDetections.keySet());
        }

        return new JiraSafePayload(
                request.operation(),
                request.ticketRef(),
                request.issueLane(),
                request.textFields(),
                checks);
    }

    /**
     * Checks a request without building a payload, for pre-flight validation in the UI and REST
     * layers. Returns every violation rather than throwing, so a user can be shown which field
     * is the problem before they submit.
     */
    public List<EgressViolation> check(JiraWriteRequest request) {
        try {
            sanitise(request);
            return List.of();
        } catch (EgressViolationException e) {
            return e.violations();
        }
    }
}
