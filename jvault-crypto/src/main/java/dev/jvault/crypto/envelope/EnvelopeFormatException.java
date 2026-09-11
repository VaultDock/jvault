package dev.jvault.crypto.envelope;

import java.io.IOException;

/** The bytes are not a well-formed jvault encrypted object. */
public class EnvelopeFormatException extends IOException {

    public EnvelopeFormatException(String message) {
        super(message);
    }
}
