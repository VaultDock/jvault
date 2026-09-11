package dev.jvault.content;

import dev.jvault.domain.common.Classification;
import dev.jvault.domain.placement.LinkPlacement;
import dev.jvault.domain.placement.PartType;
import dev.jvault.domain.placement.Placement;
import dev.jvault.domain.placement.PlacementContext;
import dev.jvault.domain.placement.PlacementResolver;
import dev.jvault.domain.placement.PolicySet;
import dev.jvault.domain.placement.ResolvedPlacement;
import dev.jvault.domain.placement.SurrogateRenderer;
import dev.jvault.domain.placement.SurrogateToken;
import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.outbox.OutboxEntry;
import dev.jvault.outbox.OutboxRepository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates a ticket: resolve placement, store what must leave Jira, build the Jira-safe payload,
 * enqueue the effects.
 *
 * <p>This is the single code path behind the UI, the REST API and the Kafka consumer. Each of
 * those builds a {@link TicketCommand} and supplies an identity; nothing else about them differs,
 * which is what makes the three-way conformance promise testable rather than aspirational.
 *
 * <p><strong>It does not call Jira.</strong> Every Jira mutation becomes an outbox row, written in
 * the same unit of work as the domain change, and the dispatcher owns delivery. That is what
 * keeps per-issue rate limiting, retries and the ambiguity protocol in one place instead of
 * scattered across three entry points.
 *
 * <p>Ordering is content-first and deliberate: content that exists without an issue is an orphan
 * the reconciler can clean up quietly, while an issue that exists without its content is a
 * visible broken link in Jira (docs/03-architecture.md 3.5).
 */
public final class TicketCreationService {

    private final PolicySet policies;
    private final ContentService contentService;
    private final TicketRepository tickets;
    private final OutboxRepository outbox;
    private final LinkFactory links;
    private final ContentService.IdGenerator ids;
    private final Clock clock;

    public TicketCreationService(PolicySet policies,
                                 ContentService contentService,
                                 TicketRepository tickets,
                                 OutboxRepository outbox,
                                 LinkFactory links,
                                 ContentService.IdGenerator ids,
                                 Clock clock) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.contentService = Objects.requireNonNull(contentService, "contentService");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.links = Objects.requireNonNull(links, "links");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result create(TicketCommand command) {
        Objects.requireNonNull(command, "command");

        var candidate = new TicketRecord(ids.newContentRef(), command.deploymentId(),
                command.projectKey(), command.issueTypeId(), command.dedupeKey(),
                command.correlationId(), command.origin(), Map.of(), List.of(),
                TicketRecord.State.DRAFT, null, null, clock.instant());

        TicketRepository.Reservation reservation = tickets.reserve(candidate);
        if (!reservation.created()) {
            // A replay, or two consumers racing the same message. Neither may create a second
            // Jira issue; both get the same ticket reference back.
            return new Result(reservation.record(), List.of(), List.of(), true);
        }

        TicketRecord ticket = reservation.record();
        var jiraFields = new LinkedHashMap<String, String>();
        var storedParts = new ArrayList<ContentRecord>();

        for (Map.Entry<String, String> field : command.fields().entrySet()) {
            String fieldKey = field.getKey();
            String value = field.getValue();

            ResolvedPlacement placement = resolve(command, fieldKey);

            if (!placement.isExternallyStored()) {
                jiraFields.put(fieldKey, value);
                continue;
            }

            ContentRecord stored = contentService.store(new ContentService.StoreRequest(
                    ticket.ticketRef(), partTypeOf(fieldKey), fieldKey,
                    value.getBytes(StandardCharsets.UTF_8), "text/plain",
                    placement.classification(), placement.keyRing()));
            storedParts.add(stored);

            // The only thing that reaches Jira for this field. Built from an allow-listed
            // template with no access to the original value.
            jiraFields.put(fieldKey, renderSurrogate(placement, ticket, stored, fieldKey));
        }

        TicketRecord withContent = ticket.withFields(jiraFields,
                storedParts.stream().map(ContentRecord::contentRef).toList(),
                TicketRecord.State.CONTENT_STORED);
        tickets.save(withContent);

        List<OutboxEntry> effects = enqueueEffects(command, withContent, storedParts);

        TicketRecord pending = withContent.withFields(jiraFields,
                withContent.externalContentRefs(), TicketRecord.State.JIRA_PENDING);
        tickets.save(pending);

        return new Result(pending, storedParts, effects, false);
    }

    private ResolvedPlacement resolve(TicketCommand command, String fieldKey) {
        var context = new PlacementContext(command.deploymentId(), command.projectKey(),
                command.issueTypeId(), partTypeOf(fieldKey), fieldKey);
        return PlacementResolver.resolve(context, policies, command.overrides().get(fieldKey));
    }

    private String renderSurrogate(ResolvedPlacement placement,
                                   TicketRecord ticket,
                                   ContentRecord stored,
                                   String fieldKey) {
        var values = SurrogateRenderer.values();
        values.put(SurrogateToken.LINK, links.linkTo(stored.contentRef()));
        values.put(SurrogateToken.CONTENT_REF, stored.contentRef());
        values.put(SurrogateToken.TICKET_REF, ticket.ticketRef());
        values.put(SurrogateToken.TICKET_REF_SHORT, shortRef(ticket.ticketRef()));
        values.put(SurrogateToken.CLASSIFICATION, placement.classification().name());
        values.put(SurrogateToken.PART_TYPE, stored.partType().name());
        values.put(SurrogateToken.PROJECT, ticket.projectKey());
        values.put(SurrogateToken.ISSUE_TYPE, ticket.issueTypeId());
        values.put(SurrogateToken.MEDIA_TYPE, stored.mediaType());
        values.put(SurrogateToken.SIZE_HUMAN, humanSize(stored.sizeBytes()));
        values.put(SurrogateToken.VERSION_NO, String.valueOf(stored.versionNo()));

        // Jira requires a summary and caps it at 255 characters, so that one field renders
        // through the guarded path rather than the general one.
        return "summary".equals(fieldKey)
                ? SurrogateRenderer.renderSummary(placement.effectiveSurrogate(), values)
                : SurrogateRenderer.render(placement.effectiveSurrogate(), values);
    }

    private List<OutboxEntry> enqueueEffects(TicketCommand command,
                                             TicketRecord ticket,
                                             List<ContentRecord> storedParts) {
        var effects = new ArrayList<OutboxEntry>();
        String lane = OutboxEntry.pendingLaneFor(ticket.ticketRef());
        String identity = ticket.origin() == null ? "INTEGRATION:default" : ticket.origin().identityRef();

        // The create carries what the ambiguity protocol will need if its outcome is ever
        // unknown: the project to search, the correlation id to match, and the summary to fall
        // back on (docs/12-reliability.md 12.4.2).
        var createRef = new LinkedHashMap<String, String>();
        createRef.put("projectKey", ticket.projectKey());
        createRef.put("issueTypeId", ticket.issueTypeId());
        if (ticket.correlationId() != null) {
            createRef.put("correlationId", ticket.correlationId());
        }
        if (ticket.expectedSummary() != null) {
            createRef.put("expectedSummary", ticket.expectedSummary());
        }

        effects.add(outbox.append(OutboxEntry.pending(ticket.ticketRef(), command.deploymentId(),
                lane, JiraOperation.CREATE_ISSUE, "create-issue", createRef, identity,
                clock.instant())));

        effects.add(outbox.append(OutboxEntry.pending(ticket.ticketRef(), command.deploymentId(),
                lane, JiraOperation.SET_PROPERTY, "prop:jvault.origin",
                Map.of("ticketRef", ticket.ticketRef(),
                        "correlationId", String.valueOf(ticket.correlationId())),
                identity, clock.instant())));

        for (ContentRecord part : storedParts) {
            ResolvedPlacement placement = resolve(command, part.fieldKey());
            if (!placement.linkPlacements().contains(LinkPlacement.REMOTE_LINK)) {
                continue;
            }
            // globalId makes this write idempotent: Jira upserts on it, so a retry updates the
            // existing link rather than adding a second (docs/00-verified-capabilities.md 0.6).
            effects.add(outbox.append(OutboxEntry.pending(ticket.ticketRef(),
                    command.deploymentId(), lane, JiraOperation.UPSERT_REMOTE_LINK,
                    "remote-link:" + part.contentRef(),
                    Map.of("contentRef", part.contentRef(),
                            "globalId", "jvault:content:" + part.contentRef()),
                    identity, clock.instant())));
        }
        return effects;
    }

    private static PartType partTypeOf(String fieldKey) {
        return switch (fieldKey) {
            case "summary" -> PartType.SUMMARY;
            case "description" -> PartType.DESCRIPTION;
            case "environment", "body" -> PartType.BODY;
            default -> PartType.CUSTOM_FIELD;
        };
    }

    private static String shortRef(String ticketRef) {
        return ticketRef.length() <= 8 ? ticketRef : ticketRef.substring(0, 8);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }

    /**
     * @param duplicate true when this command matched an existing dedupe key and nothing new was
     *                  created — the outcome a Kafka replay must produce
     */
    public record Result(TicketRecord ticket,
                         List<ContentRecord> storedParts,
                         List<OutboxEntry> effects,
                         boolean duplicate) {
    }

    /** Classification used when a policy names none. */
    static Classification defaultClassification() {
        return Classification.INTERNAL;
    }

    /** Placement requested when a command names none. */
    static Placement defaultPlacement() {
        return Placement.JIRA;
    }
}
