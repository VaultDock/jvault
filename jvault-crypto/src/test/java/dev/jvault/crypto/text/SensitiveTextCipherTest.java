package dev.jvault.crypto.text;

import dev.jvault.crypto.kms.KeyManagementService;
import dev.jvault.crypto.kms.LocalKeyManagementService;
import dev.jvault.domain.common.SensitiveValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SensitiveTextCipherTest {

    private static final String RING = "sec-restricted";
    private static final String FILENAME = "2026-Q3-layoffs-final.xlsx";

    private SensitiveTextCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new SensitiveTextCipher(LocalKeyManagementService.withKeyRings(RING, "other-ring"));
    }

    @Test
    @DisplayName("a sealed value comes back exactly")
    void roundTrips() {
        var sealed = cipher.seal(RING, SensitiveValue.of(FILENAME, "filename"), "ct-1");

        SensitiveValue opened = cipher.open(RING, sealed, "ct-1", "filename");

        assertThat(opened.reveal()).isEqualTo(FILENAME);
        assertThat(opened.label()).isEqualTo("filename");
    }

    @Test
    @DisplayName("the name itself is nowhere in what gets stored")
    void ciphertextRevealsNothing() {
        var sealed = cipher.seal(RING, SensitiveValue.of(FILENAME, "filename"), "ct-1");

        // The point of the whole class. A filename column in the clear would undo most of what
        // encrypting the bytes achieved.
        assertThat(new String(sealed.ciphertext())).doesNotContain("layoffs");
        assertThat(sealed.toString()).doesNotContain("layoffs");
    }

    @Test
    @DisplayName("a ciphertext moved to another row does not open")
    void bindingPreventsTransplanting() {
        var sealed = cipher.seal(RING, SensitiveValue.of(FILENAME, "filename"), "ct-1");

        // Someone with write access to the table but not to the key cannot silently attach this
        // filename to a different object.
        assertThatThrownBy(() -> cipher.open(RING, sealed, "ct-2", "filename"))
                .isInstanceOf(SensitiveTextCipher.SealingException.class);
    }

    @Test
    @DisplayName("a tampered ciphertext is refused rather than partly returned")
    void tamperingIsDetected() {
        var sealed = cipher.seal(RING, SensitiveValue.of(FILENAME, "filename"), "ct-1");

        byte[] tampered = sealed.ciphertext();
        tampered[tampered.length - 1] ^= 0x01;
        var forged = new SensitiveTextCipher.Sealed(sealed.kekId(), sealed.wrappedKey(), tampered);

        assertThatThrownBy(() -> cipher.open(RING, forged, "ct-1", "filename"))
                .isInstanceOf(SensitiveTextCipher.SealingException.class);
    }

    @Test
    @DisplayName("the wrong key ring cannot open it")
    void wrongRingIsRefused() {
        var sealed = cipher.seal(RING, SensitiveValue.of(FILENAME, "filename"), "ct-1");

        assertThatThrownBy(() -> cipher.open("other-ring", sealed, "ct-1", "filename"))
                .isInstanceOfAny(KeyManagementService.KeyUnwrapException.class,
                        SensitiveTextCipher.SealingException.class);
    }

    @Test
    @DisplayName("the same name sealed twice gives different bytes")
    void sealingIsNotDeterministic() {
        var first = cipher.seal(RING, SensitiveValue.of(FILENAME, "filename"), "ct-1");
        var second = cipher.seal(RING, SensitiveValue.of(FILENAME, "filename"), "ct-1");

        // Otherwise equal filenames would be visible as equal ciphertexts, which is enough to
        // group every attachment from the same monthly report without decrypting anything.
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    @DisplayName("a truncated value is refused, not read past")
    void truncatedValuesAreRefused() {
        var stub = new SensitiveTextCipher.Sealed("kek-1", new byte[32], new byte[4]);

        assertThatThrownBy(() -> cipher.open(RING, stub, "ct-1", "filename"))
                .isInstanceOf(SensitiveTextCipher.SealingException.class);
    }
}
