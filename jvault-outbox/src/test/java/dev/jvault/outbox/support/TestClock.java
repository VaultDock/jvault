package dev.jvault.outbox.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock the test moves by hand, so schedules are asserted exactly rather than approximately. */
public final class TestClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public TestClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private TestClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public static TestClock at(String iso) {
        return new TestClock(Instant.parse(iso));
    }

    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
        return new TestClock(now, z);
    }
}
