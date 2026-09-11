package dev.jvault.outbox.support;

import java.util.random.RandomGenerator;

/**
 * A {@link RandomGenerator} with a fixed {@code nextDouble}, so jitter becomes a known multiplier
 * and backoff delays can be asserted to the millisecond.
 */
public final class FixedRandom implements RandomGenerator {

    private final double value;

    public FixedRandom(double value) {
        this.value = value;
    }

    /** Jitter multiplier lands exactly midway between the configured bounds. */
    public static FixedRandom midpoint() {
        return new FixedRandom(0.5);
    }

    @Override
    public double nextDouble() {
        return value;
    }

    @Override
    public long nextLong() {
        return Double.doubleToLongBits(value);
    }
}
