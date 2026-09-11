package dev.jvault.domain.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveValueTest {

    private static final String CANARY = "AKIAIOSFODNN7EXAMPLE-canary-value";

    @Test
    @DisplayName("toString redacts, so accidental stringification cannot leak")
    void toStringRedacts() {
        var value = SensitiveValue.of(CANARY, "description");

        assertThat(value.toString()).doesNotContain(CANARY);
        assertThat(value.toString()).isEqualTo("«redacted»:description");
    }

    @Test
    @DisplayName("the label survives redaction so a log line still says what was withheld")
    void labelIsVisible() {
        assertThat(SensitiveValue.of(CANARY, "customfield_10010").toString())
                .contains("customfield_10010");
    }

    @Test
    @DisplayName("string concatenation and String.valueOf go through toString")
    void implicitStringificationIsSafe() {
        var value = SensitiveValue.of(CANARY, "body");

        assertThat("field=" + value).doesNotContain(CANARY);
        assertThat(String.valueOf(value)).doesNotContain(CANARY);
        assertThat(String.format("%s", value)).doesNotContain(CANARY);
    }

    @Test
    @DisplayName("reveal is the only way out")
    void revealReturnsTheValue() {
        assertThat(SensitiveValue.of(CANARY, "body").reveal()).isEqualTo(CANARY);
    }

    @Test
    @DisplayName("equality is on content, not label")
    void equalityIgnoresLabel() {
        assertThat(SensitiveValue.of("same", "a")).isEqualTo(SensitiveValue.of("same", "b"));
        assertThat(SensitiveValue.of("a", "x")).isNotEqualTo(SensitiveValue.of("b", "x"));
    }
}
