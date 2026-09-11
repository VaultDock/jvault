package dev.jvault.authz;

import java.util.Objects;

/**
 * Someone or something a grant can name.
 *
 * <p>Groups and roles come from the identity provider, and are matched by their external
 * identifier rather than by display name — a renamed group must not silently change who has
 * access.
 */
public record Principal(Kind kind, String externalId) {

    public enum Kind {USER, GROUP, ROLE, SERVICE}

    public Principal {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(externalId, "externalId");
        if (externalId.isBlank()) {
            throw new IllegalArgumentException("externalId must not be blank");
        }
    }

    public static Principal user(String id) {
        return new Principal(Kind.USER, id);
    }

    public static Principal group(String id) {
        return new Principal(Kind.GROUP, id);
    }

    public static Principal role(String id) {
        return new Principal(Kind.ROLE, id);
    }

    public static Principal service(String clientId) {
        return new Principal(Kind.SERVICE, clientId);
    }

    @Override
    public String toString() {
        return kind + ":" + externalId;
    }
}
