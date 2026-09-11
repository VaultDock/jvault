package dev.jvault.jira.egress;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Canonical form used for content comparison in the egress check.
 *
 * <p>Normalisation exists so that trivial reformatting does not defeat the check. A value that
 * reaches Jira re-wrapped, re-cased, or with compatibility-equivalent Unicode characters is
 * still the same content and must still be caught.
 *
 * <p>This is not a security boundary against a determined adversary — someone who wants to get
 * content past the guard can paraphrase it. It is a boundary against the realistic failure: a
 * refactor that passes the wrong variable.
 */
final class TextNormalizer {

    private TextNormalizer() {
    }

    static String normalize(String input) {
        if (input == null) {
            return "";
        }
        // NFKC folds compatibility variants (full-width forms, ligatures) onto their canonical
        // equivalents, so visually identical text normalises identically.
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC);
        normalized = normalized.toLowerCase(Locale.ROOT);
        // Collapse every run of whitespace, including newlines, to one space: line wrapping
        // must not change the comparison.
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized.strip();
    }
}
