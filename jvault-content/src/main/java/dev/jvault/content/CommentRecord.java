package dev.jvault.content;

import dev.jvault.domain.placement.Placement;

import java.time.Instant;
import java.util.Objects;

/**
 * A comment on a ticket.
 *
 * <p>{@code jiraBody} is always Jira-safe: the verbatim text when the comment is Jira-placed, the
 * rendered surrogate when it is not. Holding it here — rather than in the outbox row — is what
 * lets the outbox carry identifiers only while the assembler still has something to send.
 *
 * <p>An externally placed comment still gets a real Jira comment. Skipping it would leave holes in
 * the conversation and break Jira's notifications, watchers and @-mentions, so the Jira comment
 * exists and carries the surrogate and the link (docs/05-content-placement.md 5.5).
 *
 * @param contentRef the external body, or {@code null} when the comment lives in Jira
 * @param jiraCommentId set once Jira has confirmed the write
 */
public record CommentRecord(String commentRef,
                            String ticketRef,
                            String jiraBody,
                            String contentRef,
                            Placement placement,
                            String authorId,
                            String jiraCommentId,
                            Instant createdAt) {

    public CommentRecord {
        Objects.requireNonNull(commentRef, "commentRef");
        Objects.requireNonNull(ticketRef, "ticketRef");
        Objects.requireNonNull(jiraBody, "jiraBody");
        Objects.requireNonNull(placement, "placement");
    }

    public boolean isExternallyStored() {
        return placement.isExternallyStored();
    }
}
