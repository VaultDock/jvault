package dev.jvault.api.content;

import dev.jvault.api.error.ApiProblem;
import dev.jvault.api.security.Caller;
import dev.jvault.api.security.CallerResolver;
import dev.jvault.authz.AuthorizationDecision;
import dev.jvault.authz.ContentAuthorizationService;
import dev.jvault.authz.Permission;
import dev.jvault.authz.Scope;
import dev.jvault.content.ContentRecord;
import dev.jvault.content.ContentService;
import dev.jvault.content.TicketRecord;
import dev.jvault.content.TicketRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Serving externally stored content.
 *
 * <p>This controller is where several of the design's promises stop being prose:
 *
 * <ul>
 *   <li><strong>The link is not a capability.</strong> {@code /c/{contentRef}} carries no token,
 *       no signature and no expiry. It names content; authority comes from the caller.</li>
 *   <li><strong>Every request is authorized.</strong> Metadata, download and each range request
 *       are separate decisions. There is no "already authorized for this object" state, because
 *       that is how a revoked grant keeps working until someone reloads.</li>
 *   <li><strong>Existence is hidden from outsiders.</strong> A caller with no access to the
 *       containing space gets 404, so probing references cannot enumerate what exists. Someone
 *       inside the space gets a clear 403, because there existence is already known and a
 *       confusing 404 helps nobody.</li>
 *   <li><strong>No storage detail escapes.</strong> Bytes are streamed through jvault. No
 *       presigned URL, no bucket name, no object key, ever.</li>
 * </ul>
 */
@RestController
public class ContentAccessController {

    private final ContentService contentService;
    private final TicketRepository tickets;
    private final ContentAuthorizationService authorization;
    private final CallerResolver callers;
    private final AccessAuditor audit;
    private final String jiraConnectUrl;

    public ContentAccessController(ContentService contentService,
                                   TicketRepository tickets,
                                   ContentAuthorizationService authorization,
                                   CallerResolver callers,
                                   AccessAuditor audit,
                                   @Value("${jvault.jira.connect-url:/api/v1/jira/connections/start}")
                                   String jiraConnectUrl) {
        this.contentService = Objects.requireNonNull(contentService, "contentService");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.jiraConnectUrl = Objects.requireNonNull(jiraConnectUrl, "jiraConnectUrl");
    }

    /**
     * The permanent link written into Jira.
     *
     * <p>Deliberately outside {@code /api/v1}: it is a permanent public identifier by contract, so
     * it must not be versioned along with the API. Every Jira issue ever created carries one of
     * these, and they have to keep working.
     */
    @GetMapping("/c/{contentRef}")
    public ResponseEntity<?> followLink(@PathVariable String contentRef,
                                        HttpServletRequest request) {
        return download(contentRef, request);
    }

    @GetMapping("/api/v1/content/{contentRef}")
    public ResponseEntity<?> metadata(@PathVariable String contentRef,
                                      HttpServletRequest request) {
        return resolve(contentRef, Permission.VIEW, request, (caller, record) ->
                ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ContentMetadataResponse.of(record)));
    }

    @GetMapping("/api/v1/content/{contentRef}/download")
    public ResponseEntity<?> download(@PathVariable String contentRef,
                                      HttpServletRequest request) {
        return resolve(contentRef, Permission.DOWNLOAD, request, (caller, record) -> {
            InputStream stream = contentService.open(record);

            return ResponseEntity.ok()
                    // no-store, not no-cache: a shared machine's disk cache is exactly the place
                    // this content must not end up.
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(record))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(record.sizeBytes())
                    .body(new InputStreamResource(stream));
        });
    }

    /**
     * Resolves, authorizes and audits, then hands off to the caller's handler.
     *
     * <p>Every path through this method ends in an audit record — allows and denials alike. A
     * denial stream is the primary signal that a link has been shared outside its audience, so
     * auditing only successes would lose exactly the events worth watching.
     */
    private ResponseEntity<?> resolve(String contentRef,
                                      Permission permission,
                                      HttpServletRequest request,
                                      Handler handler) {
        Optional<Caller> maybeCaller = callers.resolve(request);
        if (maybeCaller.isEmpty()) {
            audit.record(null, contentRef, permission, "ANONYMOUS");
            return ResponseEntity.status(401).body(
                    ApiProblem.of(org.springframework.http.HttpStatus.UNAUTHORIZED,
                            "unauthenticated", "Authentication required"));
        }
        Caller caller = maybeCaller.get();

        Optional<ContentRecord> maybeRecord = contentService.find(contentRef);
        if (maybeRecord.isEmpty()) {
            audit.record(caller, contentRef, permission, "NOT_FOUND");
            return notFound();
        }
        ContentRecord record = maybeRecord.get();

        Optional<Scope> maybeScope = scopeOf(record);
        if (maybeScope.isEmpty()) {
            audit.record(caller, contentRef, permission, "ORPHANED");
            return notFound();
        }
        Scope scope = maybeScope.get();

        // Outsiders must not be able to tell a real reference from a made-up one.
        if (!authorization.canRevealExistence(caller.subject(), scope)) {
            audit.record(caller, contentRef, permission, "EXISTENCE_HIDDEN");
            return notFound();
        }

        AuthorizationDecision decision =
                authorization.authorize(caller.subject(), scope, permission);
        audit.record(caller, contentRef, permission, decision.reason());

        return switch (decision.outcome()) {
            case ALLOW -> handler.handle(caller, record);
            case UNAVAILABLE -> ResponseEntity.status(503).body(ApiProblem.authorizationUnavailable());
            case DENY -> AuthorizationDecision.JIRA_NOT_CONNECTED.equals(decision.reason())
                    ? ResponseEntity.status(409).body(ApiProblem.jiraNotConnected(jiraConnectUrl))
                    : ResponseEntity.status(403).body(ApiProblem.forbidden(decision.reason()));
        };
    }

    private Optional<Scope> scopeOf(ContentRecord record) {
        return tickets.find(record.ticketRef())
                .map(ticket -> Scope.space(spaceIdOf(ticket))
                        .ticket(ticket.ticketRef())
                        .part(record.contentRef()));
    }

    /** A space is one project on one deployment, which is the unit administrators think in. */
    static String spaceIdOf(TicketRecord ticket) {
        return ticket.deploymentId() + "/" + ticket.projectKey();
    }

    /**
     * The download filename.
     *
     * <p>The stored display name is a {@link dev.jvault.domain.common.SensitiveValue} and reaches
     * the browser only here, in a response the caller has just been authorized for. Everywhere
     * else — storage keys, link titles, logs — it stays withheld.
     */
    private static String contentDisposition(ContentRecord record) {
        String name = record.displayName() == null
                ? record.contentRef()
                : record.displayName().reveal();
        String sanitised = name.replaceAll("[\"\\r\\n]", "_");
        return "attachment; filename=\"" + sanitised + "\"";
    }

    private static ResponseEntity<ProblemDetail> notFound() {
        return ResponseEntity.status(404).body(ApiProblem.notFound());
    }

    @FunctionalInterface
    private interface Handler {
        ResponseEntity<?> handle(Caller caller, ContentRecord record);
    }

    /**
     * Records an access decision.
     *
     * <p>Identifiers and reason codes only. A rising denial rate against one content reference is
     * what a shared link looks like from the inside, which is why denials are recorded as
     * prominently as successes (docs/12-reliability.md 12.9).
     */
    public interface AccessAuditor {
        void record(Caller caller, String contentRef, Permission permission, String reason);
    }

    /** What a caller may know about content without downloading it. */
    public record ContentMetadataResponse(String contentRef,
                                          String partType,
                                          String fieldKey,
                                          String classification,
                                          long sizeBytes,
                                          String mediaType,
                                          int versionNo) {

        static ContentMetadataResponse of(ContentRecord record) {
            // Note what is absent: the display name, the storage backend, the object key, the
            // wrapped key, and the digests. A metadata response is not a place to leak the
            // filename or where the bytes live.
            return new ContentMetadataResponse(record.contentRef(), record.partType().name(),
                    record.fieldKey(), record.classification().name(), record.sizeBytes(),
                    record.mediaType(), record.versionNo());
        }
    }
}
