package dev.jvault.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A node in the three-level permission tree: space → ticket → part.
 *
 * <p>A grant at a scope applies to that scope and everything beneath it, so access is normally
 * described once at the space and refined only where a ticket or a part genuinely differs.
 */
public record Scope(Type type, String id, Scope parent) {

    public enum Type {SPACE, TICKET, PART}

    public Scope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (type == Type.SPACE && parent != null) {
            throw new IllegalArgumentException("a space is the root of the tree");
        }
        if (type != Type.SPACE && parent == null) {
            throw new IllegalArgumentException(type + " must have a parent");
        }
    }

    public static Scope space(String id) {
        return new Scope(Type.SPACE, id, null);
    }

    public Scope ticket(String ticketRef) {
        return new Scope(Type.TICKET, ticketRef, this);
    }

    public Scope part(String contentRef) {
        return new Scope(Type.PART, contentRef, this);
    }

    /** This scope and its ancestors, nearest first. */
    public List<Scope> chain() {
        var chain = new ArrayList<Scope>();
        for (Scope at = this; at != null; at = at.parent()) {
            chain.add(at);
        }
        return chain;
    }

    public Scope spaceScope() {
        Scope at = this;
        while (at.parent() != null) {
            at = at.parent();
        }
        return at;
    }

    public Optional<Scope> parentScope() {
        return Optional.ofNullable(parent);
    }

    @Override
    public String toString() {
        return type + ":" + id;
    }
}
