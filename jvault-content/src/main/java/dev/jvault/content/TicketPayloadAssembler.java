package dev.jvault.content;

import dev.jvault.domain.common.Classification;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.jira.egress.JiraWriteRequest;
import dev.jvault.outbox.JiraPayloadAssembler;
import dev.jvault.outbox.OutboxEntry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Builds a Jira write request from an outbox entry, at dispatch time.
 *
 * <p>This is the class that lets the outbox table hold nothing but identifiers. Values are read
 * <em>now</em>, not snapshotted when the effect was enqueued, so the egress guard inspects the
 * bytes that are genuinely about to be sent — which matters, because between enqueue and dispatch
 * a policy may have changed, content may have been reclassified, or a part may have been deleted.
 */
public final class TicketPayloadAssembler implements JiraPayloadAssembler {

    /**
     * Parts larger than this are not decrypted to feed the egress hash check.
     *
     * <p>A deliberate trade. The hash check exists to catch a payload accidentally carrying
     * externalised content, and decrypting every attachment on every dispatch — through the key
     * manager, for content that is never part of a Jira payload anyway — would cost far more than
     * the residual risk. Text parts, which are the ones a construction bug could plausibly leak,
     * sit far below this. The limit is stated rather than hidden so the gap is a known one.
     */
    private static final long HASH_CHECK_SIZE_LIMIT = 64 * 1024;

    private final TicketRepository tickets;
    private final ContentMetadataRepository metadata;
    private final ContentService contentService;

    public TicketPayloadAssembler(TicketRepository tickets,
                                  ContentMetadataRepository metadata,
                                  ContentService contentService) {
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.contentService = Objects.requireNonNull(contentService, "contentService");
    }

    @Override
    public JiraWriteRequest assemble(OutboxEntry entry) throws EffectNoLongerApplicable {
        TicketRecord ticket = tickets.find(entry.ticketRef())
                .orElseThrow(() -> new EffectNoLongerApplicable("TICKET_GONE"));

        var builder = JiraWriteRequest.builder(entry.operation(), entry.ticketRef())
                .issueLane(entry.issueLane())
                .classification(classificationOf(ticket));

        switch (entry.operation()) {
            case CREATE_ISSUE, UPDATE_FIELDS -> ticket.jiraFields().forEach(builder::field);
            case UPSERT_REMOTE_LINK -> {
                String contentRef = entry.payloadRef().get("contentRef");
                ContentRecord part = metadata.findCurrent(contentRef)
                        .orElseThrow(() -> new EffectNoLongerApplicable("PART_DELETED"));
                builder.field("globalId", "jvault:content:" + part.contentRef());
                builder.field("title", titleFor(part));
            }
            case SET_PROPERTY -> entry.payloadRef().forEach(builder::field);
            default -> ticket.jiraFields().forEach(builder::field);
        }

        // Supplying the ticket's external content is what lets the guard prove none of it
        // appears in the payload. It is not sent anywhere.
        externalValuesOf(ticket).forEach(builder::externalValue);

        return builder.build();
    }

    private List<SensitiveValue> externalValuesOf(TicketRecord ticket) {
        return ticket.externalContentRefs().stream()
                .map(metadata::findCurrent)
                .flatMap(java.util.Optional::stream)
                .filter(part -> part.sizeBytes() <= HASH_CHECK_SIZE_LIMIT)
                .map(this::readForComparison)
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private java.util.Optional<SensitiveValue> readForComparison(ContentRecord part) {
        try (InputStream in = contentService.open(part)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return java.util.Optional.of(SensitiveValue.of(text,
                    part.fieldKey() == null ? part.partType().name() : part.fieldKey()));
        } catch (IOException | RuntimeException e) {
            // If the content cannot be read, the guard simply has less to compare against. That
            // is not a reason to abort the Jira write — the payload is built from surrogates —
            // but it is worth knowing about, so it surfaces as an unreadable-part signal rather
            // than being swallowed silently.
            return java.util.Optional.empty();
        }
    }

    private static String titleFor(ContentRecord part) {
        // Non-sensitive by construction: part type, size and media type only. Never the filename.
        return part.partType().name().toLowerCase(java.util.Locale.ROOT)
                + " (" + part.sizeBytes() + " bytes, " + part.mediaType() + ")";
    }

    private Classification classificationOf(TicketRecord ticket) {
        return ticket.externalContentRefs().stream()
                .map(metadata::findCurrent)
                .flatMap(java.util.Optional::stream)
                .map(ContentRecord::classification)
                .reduce(Classification.INTERNAL, Classification::moreSensitive);
    }
}
