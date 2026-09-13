package dev.jvault.api.attachments;

import dev.jvault.api.error.ApiProblem;
import dev.jvault.api.security.Caller;
import dev.jvault.api.security.CallerResolver;
import dev.jvault.authz.AuthorizationDecision;
import dev.jvault.authz.ContentAuthorizationService;
import dev.jvault.authz.Permission;
import dev.jvault.authz.Scope;
import dev.jvault.content.ContentRecord;
import dev.jvault.content.LinkFactory;
import dev.jvault.content.TicketAmendmentService;
import dev.jvault.content.TicketRecord;
import dev.jvault.content.TicketRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Uploading a document to a ticket.
 *
 * <p>The file is encrypted before it reaches any disk, and its name is encrypted separately:
 * {@code 2026-Q3-layoffs-final.xlsx} is frequently the most sensitive thing about a file, and a
 * filename column in the clear would undo most of what encrypting the bytes achieved
 * (docs/04-data-model.md 4.2). What Jira gets is a link and a surrogate, never the bytes.
 *
 * <p>The stream is handed to the content service unread. Buffering the file here to check
 * something about it would put a plaintext copy in this process's heap, which is precisely what
 * the streaming cipher exists to avoid.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketRef}/attachments")
public class AttachmentController {

    private final TicketAmendmentService amendments;
    private final TicketRepository tickets;
    private final ContentAuthorizationService authorization;
    private final CallerResolver callers;
    private final LinkFactory links;
    private final long maxBytes;

    public AttachmentController(TicketAmendmentService amendments,
                                TicketRepository tickets,
                                ContentAuthorizationService authorization,
                                CallerResolver callers,
                                LinkFactory links,
                                @Value("${jvault.storage.max-upload-bytes:104857600}")
                                long maxBytes) {
        this.amendments = Objects.requireNonNull(amendments, "amendments");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.links = Objects.requireNonNull(links, "links");
        this.maxBytes = maxBytes;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable String ticketRef,
                                    @RequestParam("file") MultipartFile file,
                                    HttpServletRequest request) throws IOException {
        Optional<Caller> maybeCaller = callers.resolve(request);
        if (maybeCaller.isEmpty()) {
            return ResponseEntity.status(401).body(ApiProblem.of(
                    HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required"));
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
                authorization.authorize(caller.subject(), scope, Permission.EDIT);
        if (!decision.isAllowed()) {
            return refuse(decision);
        }

        if (file.isEmpty()) {
            return ResponseEntity.status(400).body(ApiProblem.of(
                    HttpStatus.BAD_REQUEST, "empty-file", "The uploaded file has no content"));
        }
        if (file.getSize() > maxBytes) {
            // Checked before the stream is opened. Discovering the limit after writing 90 MB of
            // ciphertext means cleaning up 90 MB of ciphertext.
            return ResponseEntity.status(413).body(ApiProblem.of(
                    HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large",
                    "The file exceeds the configured limit of " + maxBytes + " bytes"));
        }

        try (InputStream content = file.getInputStream()) {
            TicketAmendmentService.AttachmentResult result = amendments.addAttachment(
                    ticketRef, safeName(file), content, mediaType(file), file.getSize());

            ContentRecord stored = result.stored();
            return ResponseEntity.status(201).body(new AttachmentResponse(
                    stored.contentRef(),
                    // The name is echoed from the request rather than read back out of the
                    // database, which would mean decrypting it to tell the uploader what they
                    // just typed.
                    safeName(file),
                    stored.sizeBytes(),
                    stored.mediaType(),
                    stored.classification().name(),
                    result.surrogate(),
                    links.linkTo(stored.contentRef())));
        } catch (UnsupportedOperationException e) {
            // Policy places attachments in Jira for this project, and that path is not built.
            // Refusing is the honest answer; storing a copy nobody asked for is not.
            return ResponseEntity.status(501).body(ApiProblem.of(
                    HttpStatus.NOT_IMPLEMENTED, "attachment-placement-unsupported",
                    "Attachments for this project are placed in Jira, which is not supported yet"));
        }
    }

    /**
     * A filename with no path in it.
     *
     * <p>Browsers have sent {@code ../../etc/passwd} as a filename before now. jvault never uses
     * this value as a path — object keys are opaque identifiers by construction — so this is a
     * second line rather than the only one, which is the right number of lines for this.
     */
    private static String safeName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "attachment";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.isBlank() ? "attachment" : name;
    }

    /** What the browser claimed, which is a hint and not a fact. */
    private static String mediaType(MultipartFile file) {
        String declared = file.getContentType();
        return declared == null || declared.isBlank() ? "application/octet-stream" : declared;
    }

    private ResponseEntity<?> refuse(AuthorizationDecision decision) {
        return switch (decision.outcome()) {
            case UNAVAILABLE ->
                    ResponseEntity.status(503).body(ApiProblem.authorizationUnavailable());
            case DENY -> ResponseEntity.status(403).body(ApiProblem.forbidden(decision.reason()));
            case ALLOW -> throw new IllegalStateException("not a refusal");
        };
    }

    /**
     * @param jiraSurrogate what Jira will show in place of the file
     * @param link          not a capability: following it is authorized afresh
     */
    public record AttachmentResponse(String contentRef,
                                     String fileName,
                                     long sizeBytes,
                                     String mediaType,
                                     String classification,
                                     String jiraSurrogate,
                                     String link) {
    }
}
