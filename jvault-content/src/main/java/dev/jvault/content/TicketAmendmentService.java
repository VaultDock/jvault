package dev.jvault.content;

import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.domain.placement.LinkPlacement;
import dev.jvault.domain.placement.PartType;
import dev.jvault.domain.placement.PlacementContext;
import dev.jvault.domain.placement.PlacementResolver;
import dev.jvault.domain.placement.PolicySet;
import dev.jvault.domain.placement.ResolvedPlacement;
import dev.jvault.domain.placement.SurrogateRenderer;
import dev.jvault.domain.placement.SurrogateToken;
import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.outbox.OutboxEntry;
import dev.jvault.outbox.OutboxRepository;

import java.io.InputStream;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adds comments and attachments to an existing ticket.
 *
 * <p>The two behave differently on purpose, and the difference is the interesting part:
 *
 * <ul>
 *   <li>An <strong>externally placed comment still gets a real Jira comment</strong>, carrying the
 *       surrogate and the link. Omitting it would leave holes in the conversation and silently
 *       break Jira's notifications, watchers and mentions — the comment shell is what keeps the
 *       ticket coherent for people working in Jira.</li>
 *   <li>An <strong>externally placed attachment reaches Jira in no form at all.</strong> There is
 *       no shell to keep, so Jira gets a remote link and nothing else. The original filename never
 *       leaves jvault: it is frequently the most sensitive thing about a file, so the link title
 *       is built from part type, size and media type instead.</li>
 * </ul>
 */
public final class TicketAmendmentService {

    private final PolicySet policies;
    private final ContentService contentService;
    private final TicketRepository tickets;
    private final CommentRepository comments;
    private final OutboxRepository outbox;
    private final LinkFactory links;
    private final ContentService.IdGenerator ids;
    private final Clock clock;

    public TicketAmendmentService(PolicySet policies,
                                  ContentService contentService,
                                  TicketRepository tickets,
                                  CommentRepository comments,
                                  OutboxRepository outbox,
                                  LinkFactory links,
                                  ContentService.IdGenerator ids,
                                  Clock clock) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.contentService = Objects.requireNonNull(contentService, "contentService");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.comments = Objects.requireNonNull(comments, "comments");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.links = Objects.requireNonNull(links, "links");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CommentResult addComment(String ticketRef, String body, String authorId) {
        TicketRecord ticket = requireTicket(ticketRef);
        ResolvedPlacement placement = resolve(ticket, PartType.COMMENT, null);

        String commentRef = ids.newContentRef();

        if (!placement.isExternallyStored()) {
            var comment = new CommentRecord(commentRef, ticketRef, body, null,
                    placement.placement(), authorId, null, clock.instant());
            comments.save(comment);
            return new CommentResult(comment, null,
                    enqueueComment(ticket, comment, placement, null));
        }

        ContentRecord stored = contentService.storeText(
                PartDescriptor.text(ticketRef, PartType.COMMENT, null,
                        placement.classification(), placement.keyRing()),
                body);

        String surrogate = renderSurrogate(placement, ticket, stored);
        var comment = new CommentRecord(commentRef, ticketRef, surrogate, stored.contentRef(),
                placement.placement(), authorId, null, clock.instant());
        comments.save(comment);

        return new CommentResult(comment, stored,
                enqueueComment(ticket, comment, placement, stored));
    }

    /**
     * @param sizeHint expected byte count, or {@code -1}. Only a hint: the authoritative size is
     *                 what was actually read, which is what the record ends up holding
     */
    public AttachmentResult addAttachment(String ticketRef,
                                          String fileName,
                                          InputStream content,
                                          String mediaType,
                                          long sizeHint) {
        TicketRecord ticket = requireTicket(ticketRef);
        ResolvedPlacement placement = resolve(ticket, PartType.ATTACHMENT, null);

        if (!placement.isExternallyStored()) {
            // Jira-placed attachments stream straight to Jira's attachment API and jvault keeps
            // no copy. That path belongs to the Jira adapter, not here — and it is not built yet,
            // so refuse loudly rather than silently storing a copy the policy did not ask for.
            throw new UnsupportedOperationException(
                    "Jira-placed attachments are not implemented yet; policy for "
                            + ticket.projectKey() + " must place attachments externally");
        }

        var descriptor = new PartDescriptor(ticketRef, PartType.ATTACHMENT, null,
                SensitiveValue.of(fileName, "attachment.fileName"), mediaType,
                placement.classification(), placement.keyRing(), null);

        ContentRecord stored = contentService.store(descriptor, content);
        String surrogate = renderSurrogate(placement, ticket, stored);

        return new AttachmentResult(stored, surrogate,
                enqueueAttachment(ticket, stored, placement));
    }

    private OutboxEntry enqueueComment(TicketRecord ticket,
                                       CommentRecord comment,
                                       ResolvedPlacement placement,
                                       ContentRecord stored) {
        var ref = new LinkedHashMap<String, String>();
        ref.put("commentRef", comment.commentRef());
        if (stored != null) {
            ref.put("contentRef", stored.contentRef());
        }

        return outbox.append(OutboxEntry.pending(ticket.ticketRef(), ticket.deploymentId(),
                laneFor(ticket), JiraOperation.ADD_COMMENT,
                "comment:" + comment.commentRef(), ref, identityOf(ticket), clock.instant()));
    }

    private OutboxEntry enqueueAttachment(TicketRecord ticket,
                                          ContentRecord stored,
                                          ResolvedPlacement placement) {
        if (!placement.linkPlacements().contains(LinkPlacement.REMOTE_LINK)) {
            // Without a link the content would be unreachable from Jira. Policy validation
            // rejects such a configuration, so reaching here means validation was bypassed.
            throw new IllegalStateException("attachment policy for " + ticket.projectKey()
                    + " places content externally but declares no remote link");
        }
        return outbox.append(OutboxEntry.pending(ticket.ticketRef(), ticket.deploymentId(),
                laneFor(ticket), JiraOperation.UPSERT_REMOTE_LINK,
                "remote-link:" + stored.contentRef(),
                Map.of("contentRef", stored.contentRef(),
                        "globalId", "jvault:content:" + stored.contentRef()),
                identityOf(ticket), clock.instant()));
    }

    private ResolvedPlacement resolve(TicketRecord ticket, PartType partType, String fieldKey) {
        return PlacementResolver.resolve(new PlacementContext(ticket.deploymentId(),
                ticket.projectKey(), ticket.issueTypeId(), partType, fieldKey), policies);
    }

    private String renderSurrogate(ResolvedPlacement placement,
                                   TicketRecord ticket,
                                   ContentRecord stored) {
        var values = SurrogateRenderer.values();
        values.put(SurrogateToken.LINK, links.linkTo(stored.contentRef()));
        values.put(SurrogateToken.CONTENT_REF, stored.contentRef());
        values.put(SurrogateToken.TICKET_REF, ticket.ticketRef());
        values.put(SurrogateToken.CLASSIFICATION, placement.classification().name());
        values.put(SurrogateToken.PART_TYPE, stored.partType().name());
        values.put(SurrogateToken.PROJECT, ticket.projectKey());
        values.put(SurrogateToken.MEDIA_TYPE, stored.mediaType());
        values.put(SurrogateToken.SIZE_HUMAN, humanSize(stored.sizeBytes()));
        values.put(SurrogateToken.VERSION_NO, String.valueOf(stored.versionNo()));
        // Deliberately absent: any token carrying the filename or the body. There is none.
        return SurrogateRenderer.render(placement.effectiveSurrogate(), values);
    }

    private TicketRecord requireTicket(String ticketRef) {
        return tickets.find(ticketRef).orElseThrow(
                () -> new ContentService.ContentException("no ticket " + ticketRef, null));
    }

    /** Once Jira has created the issue, effects serialise on the issue rather than the ticket. */
    private static String laneFor(TicketRecord ticket) {
        return ticket.jiraIssueId() == null
                ? OutboxEntry.pendingLaneFor(ticket.ticketRef())
                : "issue:" + ticket.jiraIssueId();
    }

    private static String identityOf(TicketRecord ticket) {
        return ticket.origin() == null ? "INTEGRATION:default" : ticket.origin().identityRef();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }

    public record CommentResult(CommentRecord comment, ContentRecord storedBody, OutboxEntry effect) {
    }

    public record AttachmentResult(ContentRecord stored, String surrogate, OutboxEntry effect) {
    }
}
