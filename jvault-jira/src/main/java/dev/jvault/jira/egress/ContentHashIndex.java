package dev.jvault.jira.egress;

import dev.jvault.domain.common.SensitiveValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Fingerprints the externally-placed content of one ticket so the egress guard can detect it
 * appearing in a Jira-bound payload.
 *
 * <p>Three complementary checks, because one size does not fit all content lengths:
 *
 * <ol>
 *   <li><strong>Whole-value hash</strong> — catches the common mistake of passing the wrong
 *       variable, where the entire external value becomes the field value.</li>
 *   <li><strong>Literal containment</strong> for short-to-medium values — catches an external
 *       value pasted <em>inside</em> a larger Jira-bound string, which hashing alone misses.</li>
 *   <li><strong>Word shingles</strong> for long values — catches a substantial excerpt of a long
 *       document appearing in Jira, which neither of the above would find.</li>
 * </ol>
 *
 * <p>Values shorter than {@link #MIN_MATCH_LENGTH} are deliberately <em>not</em> indexed. A
 * six-character external value would match half the English language and the resulting false
 * positives would block legitimate Jira writes. Short secrets are the content classifier's job,
 * not this one's — the two layers cover different failures and neither is sufficient alone.
 *
 * <p><strong>Lifetime.</strong> This object holds normalised plaintext in memory for the literal
 * containment check. It is built per Jira write and discarded immediately; it must never be
 * cached, stored, or held on a long-lived component.
 */
final class ContentHashIndex {

    /** Below this length, hashing produces more false positives than it prevents leaks. */
    static final int MIN_MATCH_LENGTH = 8;

    /** At or above this length, a value is long enough for shingling to be meaningful. */
    static final int SHINGLE_MIN_LENGTH = 48;

    /** Words per shingle. Six is long enough to be distinctive, short enough to survive edits. */
    static final int SHINGLE_WORDS = 6;

    private final Set<String> wholeValueHashes;
    private final Set<String> shingleHashes;
    private final List<String> literals;

    private ContentHashIndex(Set<String> wholeValueHashes,
                             Set<String> shingleHashes,
                             List<String> literals) {
        this.wholeValueHashes = wholeValueHashes;
        this.shingleHashes = shingleHashes;
        this.literals = literals;
    }

    static ContentHashIndex of(Collection<SensitiveValue> externalValues) {
        var whole = new HashSet<String>();
        var shingles = new HashSet<String>();
        var literals = new ArrayList<String>();

        for (SensitiveValue value : externalValues) {
            String normalized = TextNormalizer.normalize(value.reveal());
            if (normalized.length() < MIN_MATCH_LENGTH) {
                continue;
            }
            whole.add(sha256Hex(normalized));
            literals.add(normalized);
            if (normalized.length() >= SHINGLE_MIN_LENGTH) {
                shingles.addAll(shinglesOf(normalized));
            }
        }
        return new ContentHashIndex(Set.copyOf(whole), Set.copyOf(shingles), List.copyOf(literals));
    }

    /** True when the index is empty, in which case no check is possible or needed. */
    boolean isEmpty() {
        return wholeValueHashes.isEmpty() && shingleHashes.isEmpty() && literals.isEmpty();
    }

    /**
     * @return the kind of match found, or {@code null} when the candidate is clean
     */
    MatchKind match(String candidate) {
        String normalized = TextNormalizer.normalize(candidate);
        if (normalized.length() < MIN_MATCH_LENGTH) {
            return null;
        }
        if (wholeValueHashes.contains(sha256Hex(normalized))) {
            return MatchKind.WHOLE_VALUE;
        }
        for (String literal : literals) {
            if (normalized.contains(literal)) {
                return MatchKind.CONTAINED_LITERAL;
            }
        }
        if (normalized.length() >= SHINGLE_MIN_LENGTH && !shingleHashes.isEmpty()) {
            for (String shingle : shinglesOf(normalized)) {
                if (shingleHashes.contains(shingle)) {
                    return MatchKind.SHARED_EXCERPT;
                }
            }
        }
        return null;
    }

    /** Overlapping word windows, hashed. Overlapping so an excerpt boundary cannot slip through. */
    private static Set<String> shinglesOf(String normalized) {
        String[] words = normalized.split(" ");
        if (words.length < SHINGLE_WORDS) {
            return Set.of();
        }
        var out = new HashSet<String>();
        for (int i = 0; i + SHINGLE_WORDS <= words.length; i++) {
            var sb = new StringBuilder();
            for (int j = i; j < i + SHINGLE_WORDS; j++) {
                if (j > i) sb.append(' ');
                sb.append(words[j]);
            }
            out.add(sha256Hex(sb.toString()));
        }
        return out;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }

    /** How a candidate matched. Reported in violations so operators can tell the cases apart. */
    enum MatchKind {
        WHOLE_VALUE,
        CONTAINED_LITERAL,
        SHARED_EXCERPT
    }
}
