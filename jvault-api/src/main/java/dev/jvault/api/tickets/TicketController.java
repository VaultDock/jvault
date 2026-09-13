package dev.jvault.api.tickets;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.api.error.ApiProblem;
import dev.jvault.api.idempotency.IdempotencyService;
import dev.jvault.api.security.Caller;
import dev.jvault.api.security.CallerResolver;
import dev.jvault.authz.AuthorizationDecision;
import dev.jvault.authz.ContentAuthorizationService;
import dev.jvault.authz.Permission;
import dev.jvault.authz.Scope;
import dev.jvault.content.ContentRecord;
import dev.jvault.content.LinkFactory;
import dev.jvault.content.TicketCommand;
import dev.jvault.content.TicketCreationService;
import dev.jvault.content.TicketRecord;
import dev.jvault.content.ContentMetadataRepository;
import dev.jvault.content.TicketRepository;
import dev.jvault.domain.placement.Placement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Creating and reading tickets.
 *
 * <p>A thin adapter. It authenticates, authorizes, handles the idempotency key, and hands a
 * {@link TicketCommand} to the same service the Kafka consumer uses — which is what keeps the two
 * entry points from drifting apart (docs/06-rest-api.md 6.3).
 *
 * <p>Creation answers <strong>202, not 201</strong>. The content is stored and the Jira write is
 * queued, but the issue does not exist yet: the outbox owns delivery so that rate limiting,
 * retries and the ambiguity protocol live in one place. Returning 201 with no issue key would be
 * a lie, and blocking until Jira replied would hand Jira's latency and outages straight to the
 * caller.
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TicketCreationService creation;
    private final TicketRepository tickets;
    private final ContentMetadataRepository contentMetadata;
    private final ContentAuthorizationService authorization;
    private final CallerResolver callers;
    private final IdempotencyService idempotency;
    private final LinkFactory links;

    public TicketController(TicketCreationService creation,
                            TicketRepository tickets,
                            ContentMetadataRepository contentMetadata,
                            ContentAuthorizationService authorization,
                            CallerResolver callers,
                            IdempotencyService idempotency,
                            LinkFactory links) {
        this.creation = Objects.requireNonNull(creation, "creation");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.contentMetadata = Objects.requireNonNull(contentMetadata, "contentMetadata");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.links = Objects.requireNonNull(links, "links");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody CreateTicketRequest request,
                                    @RequestHeader(value = "Idempotency-Key", required = false)
                                    String idempotencyKey,
                                    HttpServletRequest httpRequest) {

        Optional<Caller> maybeCaller = callers.resolve(httpRequest);
        if (maybeCaller.isEmpty()) {
            return unauthenticated();
        }
        Caller caller = maybeCaller.get();

        List<ApiProblem.FieldError> invalid = request.validate();
        if (!invalid.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiProblem.fieldValidation(invalid));
        }

        Scope space = Scope.space(request.deploymentId() + "/" + request.projectKey());
        AuthorizationDecision decision =
                authorization.authorize(caller.subject(), space, Permission.CREATE);
        if (!decision.isAllowed()) {
            return refuse(decision);
        }

        var begin = idempotency.begin(idempotencyKey, caller.principal().toString(),
                serialise(request));
        switch (begin.kind()) {
            case REPLAY -> {
                // The client cannot tell whether their first attempt or this one produced it,
                // which is the entire point of the key.
                return ResponseEntity.status(begin.replay().status())
                        .header("Idempotency-Replayed", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(begin.replay().responseBody());
            }
            case IN_PROGRESS -> {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .header(HttpHeaders.RETRY_AFTER, "2")
                        .body(ApiProblem.requestInProgress());
            }
            case CONFLICTING_FINGERPRINT -> {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiProblem.idempotencyKeyReuse());
            }
            default -> { /* proceed */ }
        }

        try {
            TicketCreationService.Result result = creation.create(request.toCommand(caller));
            TicketResponse body = TicketResponse.of(result, links);
            String json = serialise(body);

            idempotency.complete(begin.scopedKey(), HttpStatus.ACCEPTED.value(), json);

            return ResponseEntity.accepted()
                    .header(HttpHeaders.LOCATION, "/api/v1/tickets/" + result.ticket().ticketRef())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);

        } catch (RuntimeException e) {
            // Release the key: the caller is expected to retry with it, and that retry has to be
            // allowed to run.
            idempotency.abandon(begin.scopedKey());
            throw e;
        }
    }

    @GetMapping("/{ticketRef}")
    public ResponseEntity<?> get(@PathVariable String ticketRef, HttpServletRequest httpRequest) {
        Optional<Caller> maybeCaller = callers.resolve(httpRequest);
        if (maybeCaller.isEmpty()) {
            return unauthenticated();
        }
        Caller caller = maybeCaller.get();

        Optional<TicketRecord> maybeTicket = tickets.find(ticketRef);
        if (maybeTicket.isEmpty()) {
            return ResponseEntity.status(404).body(ApiProblem.notFound());
        }
        TicketRecord ticket = maybeTicket.get();

        Scope scope = Scope.space(ticket.deploymentId() + "/" + ticket.projectKey())
                .ticket(ticket.ticketRef());

        // Outsiders get the same answer for a real ticket as for an invented one.
        if (!authorization.canRevealExistence(caller.subject(), scope)) {
            return ResponseEntity.status(404).body(ApiProblem.notFound());
        }

        AuthorizationDecision decision =
                authorization.authorize(caller.subject(), scope, Permission.VIEW);
        if (!decision.isAllowed()) {
            return refuse(decision);
        }
        // The parts, not an empty list: a ticket view that cannot say which fields left Jira is
        // a ticket view nobody would open. The content itself is not here — each part carries a
        // link, and following one is authorized separately and audited.
        return ResponseEntity.ok(
                TicketResponse.of(ticket, contentMetadata.partsOf(ticket.ticketRef()), links));
    }

    private ResponseEntity<?> refuse(AuthorizationDecision decision) {
        return switch (decision.outcome()) {
            case UNAVAILABLE -> ResponseEntity.status(503).body(ApiProblem.authorizationUnavailable());
            case DENY -> AuthorizationDecision.JIRA_NOT_CONNECTED.equals(decision.reason())
                    ? ResponseEntity.status(409).body(
                            ApiProblem.jiraNotConnected("/api/v1/jira/connections/start"))
                    : ResponseEntity.status(403).body(ApiProblem.forbidden(decision.reason()));
            case ALLOW -> throw new IllegalStateException("not a refusal");
        };
    }

    private static ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(401).body(ApiProblem.of(
                HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required"));
    }

    private static String serialise(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise a response", e);
        }
    }

    /**
     * @param placementOverrides honoured only where policy allows, and only toward more
     *                           protection. A caller can push a field out of Jira; nothing they
     *                           send can pull one back in
     */
    public record CreateTicketRequest(String deploymentId,
                                      String projectKey,
                                      String issueTypeId,
                                      Map<String, String> fields,
                                      Map<String, String> placementOverrides,
                                      String dedupeKey,
                                      String correlationId) {

        List<ApiProblem.FieldError> validate() {
            var problems = new ArrayList<ApiProblem.FieldError>();
            if (isBlank(deploymentId)) {
                problems.add(ApiProblem.FieldError.of("deploymentId", "REQUIRED"));
            }
            if (isBlank(projectKey)) {
                problems.add(ApiProblem.FieldError.of("projectKey", "REQUIRED"));
            }
            if (isBlank(issueTypeId)) {
                problems.add(ApiProblem.FieldError.of("issueTypeId", "REQUIRED"));
            }
            if (fields == null || isBlank(fields.get("summary"))) {
                // Jira requires one on every issue, so refusing here is cheaper than discovering
                // it three layers down when the outbox row will not dispatch.
                problems.add(ApiProblem.FieldError.of("fields.summary", "REQUIRED"));
            }
            return problems;
        }

        TicketCommand toCommand(Caller caller) {
            var builder = TicketCommand.builder(deploymentId, projectKey, issueTypeId)
                    .dedupeKey(dedupeKey)
                    .correlationId(correlationId)
                    .origin(TicketCommand.Origin.ui(
                            caller.principal().externalId(), caller.principal().externalId()));

            if (fields != null) {
                fields.forEach(builder::field);
            }
            if (placementOverrides != null) {
                placementOverrides.forEach((field, placement) ->
                        builder.override(field, Placement.valueOf(placement)));
            }
            return builder.build();
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    /**
     * What a caller gets back.
     *
     * <p>{@code parts} names externally stored content and links to it; it never carries the
     * content itself, and the links are not capabilities — following one is authorized afresh.
     */
    public record TicketResponse(String ticketRef,
                                 String state,
                                 String issueKey,
                                 Map<String, String> jiraFields,
                                 List<Part> parts) {

        static TicketResponse of(TicketCreationService.Result result, LinkFactory links) {
            return of(result.ticket(), result.storedParts(), links);
        }

        static TicketResponse of(TicketRecord ticket, List<ContentRecord> parts, LinkFactory links) {
            var partResponses = new ArrayList<Part>();
            for (ContentRecord part : parts) {
                partResponses.add(new Part(part.contentRef(), part.partType().name(),
                        part.fieldKey(), part.classification().name(), part.mediaType(),
                        links.linkTo(part.contentRef())));
            }
            return new TicketResponse(ticket.ticketRef(), ticket.state().name(),
                    ticket.jiraIssueKey(), new LinkedHashMap<>(ticket.jiraFields()),
                    List.copyOf(partResponses));
        }

        /**
         * @param mediaType what the bytes are, so a viewer can decide how to show them. Not the
         *                  filename, which is sensitive and stays encrypted until somebody is
         *                  authorized for the content itself
         */
        public record Part(String contentRef, String partType, String fieldKey,
                           String classification, String mediaType, String link) {
        }
    }
}
