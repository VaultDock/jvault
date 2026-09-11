package dev.jvault.outbox.support;

import dev.jvault.outbox.TicketStateSink;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class RecordingTicketStateSink implements TicketStateSink {

    public record Active(String ticketRef, String issueId, String issueKey) {
    }

    public record Failed(String ticketRef, String errorCode) {
    }

    private final List<Active> active = new ArrayList<>();
    private final List<AmbiguityContext> ambiguous = new ArrayList<>();
    private final List<Failed> failed = new ArrayList<>();

    @Override
    public void ticketBecameActive(String ticketRef, String issueId, String issueKey, Instant when) {
        active.add(new Active(ticketRef, issueId, issueKey));
    }

    @Override
    public void ticketBecameAmbiguous(String ticketRef, AmbiguityContext context) {
        ambiguous.add(context);
    }

    @Override
    public void ticketFailed(String ticketRef, String errorCode, Instant when) {
        failed.add(new Failed(ticketRef, errorCode));
    }

    public List<Active> active() {
        return active;
    }

    public List<AmbiguityContext> ambiguous() {
        return ambiguous;
    }

    public List<Failed> failed() {
        return failed;
    }
}
