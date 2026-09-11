package dev.jvault.domain.placement;

/** Where the real value of a content part lives. */
public enum Placement {

    /** Jira holds the value. */
    JIRA,

    /** Jira holds a surrogate and a link; jvault holds the value, encrypted. */
    EXTERNAL,

    /** Jira holds the value with sensitive sections replaced by markers and links. */
    SPLIT;

    /**
     * How protective each placement is. Used when two configuration surfaces both express an
     * opinion — a Kafka mapping and a placement policy, or a policy and a user override.
     *
     * <p>SPLIT sits between JIRA and EXTERNAL: some of the content still reaches Jira.
     */
    private int protectiveness() {
        return switch (this) {
            case JIRA -> 0;
            case SPLIT -> 1;
            case EXTERNAL -> 2;
        };
    }

    /**
     * The more protective of two placements.
     *
     * <p>This is the rule that lets a Kafka mapping or a user override <em>tighten</em>
     * placement but never loosen it (docs/07-kafka.md 7.3, docs/05-content-placement.md 5.1).
     * Keeping it here, as a total function on the enum, means no call site gets to invent its
     * own combination rule.
     */
    public static Placement moreProtective(Placement a, Placement b) {
        return a.protectiveness() >= b.protectiveness() ? a : b;
    }

    public boolean isExternallyStored() {
        return this == EXTERNAL || this == SPLIT;
    }
}
