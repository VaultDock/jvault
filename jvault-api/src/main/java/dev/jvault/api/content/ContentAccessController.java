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
import dev.jvault.content.LinkFactory;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    /** A preview is a preview; past this it is a file, and a browser tab is the wrong place. */
    private static final long MAX_RENDER_BYTES = 2L * 1024 * 1024;

    /**
     * The only types served inline, and the list is short on purpose.
     *
     * <p>SVG is absent: it is a document format that can carry script, and serving one inline
     * from this origin would run that script with this origin's privileges. It is previewed as
     * "cannot preview" instead, which is a small loss against a large hole.
     */
    private static final java.util.Set<String> INLINE_IMAGE_TYPES = java.util.Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");

    private final ContentService contentService;
    private final TicketRepository tickets;
    private final ContentAuthorizationService authorization;
    private final CallerResolver callers;
    private final AccessAuditor audit;
    private final LinkFactory links;
    private final String jiraConnectUrl;

    public ContentAccessController(ContentService contentService,
                                   TicketRepository tickets,
                                   ContentAuthorizationService authorization,
                                   CallerResolver callers,
                                   AccessAuditor audit,
                                   LinkFactory links,
                                   @Value("${jvault.jira.connect-url:/api/v1/jira/connections/start}")
                                   String jiraConnectUrl) {
        this.contentService = Objects.requireNonNull(contentService, "contentService");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.links = Objects.requireNonNull(links, "links");
        this.jiraConnectUrl = Objects.requireNonNull(jiraConnectUrl, "jiraConnectUrl");
    }

    /**
     * The permanent link written into Jira.
     *
     * <p>Deliberately outside {@code /api/v1}: it is a permanent public identifier by contract, so
     * it must not be versioned along with the API. Every Jira issue ever created carries one of
     * these, and they have to keep working.
     *
     * <p>It opens the ticket, where the content is <em>shown</em>. It used to serve the bytes
     * with an attachment disposition, which meant following a link out of a Jira issue put the
     * secured file in the reader's downloads folder — the one place the whole system exists to
     * keep it out of. Taking a copy is still possible for whoever is granted
     * {@link Permission#DOWNLOAD}, but it is now a deliberate act rather than the effect of
     * clicking a link.
     */
    @GetMapping("/c/{contentRef}")
    public ResponseEntity<?> followLink(@PathVariable String contentRef,
                                        HttpServletRequest request) {
        return resolve(contentRef, Permission.VIEW, request, (caller, record) ->
                ResponseEntity.status(303)
                        .header(HttpHeaders.LOCATION, links.linkToTicket(record.ticketRef()))
                        .cacheControl(CacheControl.noStore())
                        .build());
    }

    @GetMapping("/api/v1/content/{contentRef}")
    public ResponseEntity<?> metadata(@PathVariable String contentRef,
                                      HttpServletRequest request) {
        return resolve(contentRef, Permission.VIEW, request, (caller, record) ->
                ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ContentMetadataResponse.of(record)));
    }

    /**
     * The content, rendered for reading rather than for keeping.
     *
     * <p>Under {@link Permission#VIEW} rather than {@link Permission#DOWNLOAD}, which is the
     * distinction the permission set was built around: "this group may read the incident
     * narrative in the browser but must not take a copy of the evidence file onto a laptop".
     *
     * <p>What that buys is real but bounded, and worth stating rather than implying. The bytes
     * reach the browser, so anyone who can read this can screenshot it or fetch it with the
     * developer tools open. What this prevents is the ordinary path — no attachment disposition,
     * no file on disk, nothing in the downloads folder — and it makes the grant express the
     * intent, which is what an auditor is actually asking about.
     */
    @GetMapping("/api/v1/content/{contentRef}/render")
    public ResponseEntity<?> render(@PathVariable String contentRef,
                                    HttpServletRequest request) {
        return resolve(contentRef, Permission.VIEW, request, (caller, record) -> {
            if (record.sizeBytes() > MAX_RENDER_BYTES) {
                // A preview is a preview. Something this size is a file, and rendering it in a
                // browser tab helps nobody.
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(RenderedContent.tooLarge(record));
            }

            String mediaType = record.mediaType() == null ? "" : record.mediaType();
            if (INLINE_IMAGE_TYPES.contains(mediaType)) {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        // inline, and only for the types on the list above. That list and
                        // nosniff are what actually protect this: the list means the bytes are
                        // never labelled as a document format, and nosniff stops the browser
                        // deciding for itself that they are one anyway. Serving an attacker's
                        // text/html back from this origin with an inline disposition would be
                        // stored cross-site scripting with extra steps.
                        //
                        // No Content-Security-Policy header. Both the directives worth having
                        // here — sandbox, and default-src 'none' — stop the browser painting
                        // the image, the second by blocking the inline styles of the viewer it
                        // generates. A header that breaks the feature and secures nothing is
                        // worse than no header, because the next person assumes it works.
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .header("X-Content-Type-Options", "nosniff")
                        .contentType(MediaType.parseMediaType(mediaType))
                        .contentLength(record.sizeBytes())
                        .body(new InputStreamResource(contentService.open(record)));
            }

            if (!isTextual(mediaType)) {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(RenderedContent.unsupported(record));
            }

            try (InputStream stream = contentService.open(record)) {
                String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(RenderedContent.text(record, text));
            } catch (IOException e) {
                throw new IllegalStateException("could not read content for rendering", e);
            }
        });
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

    /**
     * Whether the stored bytes are text jvault can show.
     *
     * <p>Content stored from a form field has no media type of its own — it was a string, not a
     * file — so an absent type means text rather than unknown.
     */
    private static boolean isTextual(String mediaType) {
        return mediaType.isBlank()
                || mediaType.startsWith("text/")
                || mediaType.equals("application/json");
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

    /**
     * Content rendered for reading.
     *
     * @param kind     what the client should do with this: show the rich-text document, show the
     *                 text, or say plainly that jvault cannot preview it
     * @param document the parsed rich-text document, when the stored text is one. Parsed here
     *                 rather than in the browser so that a client which cannot read ADF still
     *                 gets something, and so the shape is validated once
     */
    public record RenderedContent(String kind,
                                  String mediaType,
                                  long sizeBytes,
                                  String text,
                                  com.fasterxml.jackson.databind.JsonNode document) {

        static RenderedContent text(ContentRecord record, String stored) {
            com.fasterxml.jackson.databind.JsonNode document = asDocument(stored);
            return document != null
                    ? new RenderedContent("RICH_TEXT", record.mediaType(), record.sizeBytes(),
                            null, document)
                    : new RenderedContent("TEXT", record.mediaType(), record.sizeBytes(),
                            stored, null);
        }

        static RenderedContent unsupported(ContentRecord record) {
            return new RenderedContent("UNSUPPORTED", record.mediaType(), record.sizeBytes(),
                    null, null);
        }

        static RenderedContent tooLarge(ContentRecord record) {
            return new RenderedContent("TOO_LARGE", record.mediaType(), record.sizeBytes(),
                    null, null);
        }

        /** The stored value as a rich-text document, or {@code null} if it is ordinary text. */
        private static com.fasterxml.jackson.databind.JsonNode asDocument(String stored) {
            if (!stored.stripLeading().startsWith("{")) {
                return null;
            }
            try {
                var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stored);
                return "doc".equals(parsed.path("type").asText()) ? parsed : null;
            } catch (Exception e) {
                // Text that happens to start with a brace is still somebody's description.
                return null;
            }
        }
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
