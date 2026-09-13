package dev.jvault.api.meta;

import dev.jvault.api.error.ApiProblem;
import dev.jvault.api.security.Caller;
import dev.jvault.api.security.CallerResolver;
import dev.jvault.domain.placement.PartType;
import dev.jvault.domain.placement.PlacementContext;
import dev.jvault.domain.placement.PlacementResolver;
import dev.jvault.domain.placement.PolicySet;
import dev.jvault.domain.placement.ResolvedPlacement;
import dev.jvault.jira.egress.JiraFieldEncoding;
import dev.jvault.jira.gateway.JiraMetadataGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The configuration that drives the create form.
 *
 * <p>Mostly a proxy, with one addition that is the reason it exists rather than the client calling
 * Jira directly: <strong>every field is annotated with where its value will be stored</strong>.
 * The form can then tell a user that what they type into this box is going to jvault and not into
 * Jira — before they type it, not after. Finding that out afterwards is the kind of surprise that
 * makes people paste sensitive text somewhere else instead.
 *
 * <p>The second addition is honesty about parity. Each field carries a support level, so a type
 * jvault renders read-only says so in the form rather than silently discarding what was entered
 * (docs/02-jira-parity-scope.md 2.3).
 */
@RestController
@RequestMapping("/api/v1/meta")
public class MetadataController {

    private final JiraMetadataGateway metadata;
    private final PolicySet policies;
    private final CallerResolver callers;
    private final String deploymentId;
    private final String jiraBaseUrl;

    public MetadataController(JiraMetadataGateway metadata,
                              PolicySet policies,
                              CallerResolver callers,
                              String deploymentId,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${jvault.jira.base-url:}") String jiraBaseUrl) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId");
        this.jiraBaseUrl = jiraBaseUrl == null ? "" : trimTrailingSlash(jiraBaseUrl);
    }

    @GetMapping("/projects")
    public ResponseEntity<?> projects(HttpServletRequest request) {
        return authenticated(request, caller -> ResponseEntity.ok(metadata.projects()));
    }

    /**
     * The account jvault acts as, and the language its Jira answers arrive in.
     *
     * <p>The locale is not decoration. Jira returns field names in the authenticated account's
     * language and ignores Accept-Language on createmeta, so with a service account every user
     * sees the same field labels regardless of their own Jira setting. The client needs to know
     * which language that is, and needs to be able to say so.
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        return authenticated(request, caller -> {
            JiraMetadataGateway.CurrentUser jiraAccount = metadata.currentUser();
            return ResponseEntity.ok(new Identity(
                    caller.principal().externalId(),
                    jiraAccount.displayName(),
                    jiraAccount.locale(),
                    deploymentId,
                    jiraBaseUrl));
        });
    }

    /**
     * People a user field can be set to.
     *
     * <p>Jira accepts an account id and nothing else, and an account id is the last thing a
     * person knows about a colleague. Without this the control is a text box asking for a UUID.
     */
    @GetMapping("/projects/{projectKey}/users")
    public ResponseEntity<?> users(@PathVariable String projectKey,
                                   @RequestParam(defaultValue = "") String query,
                                   @RequestParam(defaultValue = "true") boolean assignable,
                                   HttpServletRequest request) {
        return authenticated(request, caller ->
                ResponseEntity.ok(metadata.searchUsers(projectKey, query, assignable)));
    }

    /**
     * Issues that could be a parent.
     *
     * <p>A parent field takes an issue key, and while a key is at least recognisable, nobody
     * remembers which of forty of them is the epic they meant.
     */
    @GetMapping("/projects/{projectKey}/issues")
    public ResponseEntity<?> issues(@PathVariable String projectKey,
                                    @RequestParam(defaultValue = "") String query,
                                    @RequestParam(required = false) String issueType,
                                    HttpServletRequest request) {
        // The type being created decides the answer: a parent sits exactly one level above it.
        return authenticated(request, caller ->
                ResponseEntity.ok(metadata.searchIssues(projectKey, query, issueType)));
    }

    @GetMapping("/projects/{projectKey}/issuetypes")
    public ResponseEntity<?> issueTypes(@PathVariable String projectKey,
                                        HttpServletRequest request) {
        return authenticated(request, caller -> ResponseEntity.ok(metadata.issueTypes(projectKey)));
    }

    @GetMapping("/projects/{projectKey}/issuetypes/{issueTypeId}/fields")
    public ResponseEntity<?> fields(@PathVariable String projectKey,
                                    @PathVariable String issueTypeId,
                                    HttpServletRequest request) {
        return authenticated(request, caller -> {
            var annotated = new ArrayList<FormField>();
            for (JiraMetadataGateway.FieldMeta field : metadata.fields(projectKey, issueTypeId)) {
                annotated.add(annotate(field, projectKey, issueTypeId));
            }
            return ResponseEntity.ok(new FormDefinition(projectKey, issueTypeId, annotated));
        });
    }

    private FormField annotate(JiraMetadataGateway.FieldMeta field,
                               String projectKey,
                               String issueTypeId) {
        ResolvedPlacement placement = PlacementResolver.resolve(
                new PlacementContext(deploymentId, projectKey, issueTypeId,
                        partTypeOf(field.key()), field.key()),
                policies);

        return new FormField(
                field.key(), field.name(), field.required(), field.schemaType(),
                field.customType(), field.allowedValues(), field.hasMoreOptions(),
                placement.placement().name(),
                placement.isExternallyStored() ? placement.classification().name() : null,
                placement.allowOverride(),
                supportLevelOf(field).name(),
                controlFor(field).name());
    }

    /**
     * Which control the form should render.
     *
     * <p>Derived from the same encoding table the payload uses, so the widget that collects a
     * value and the JSON that carries it cannot disagree about what the value is. A date picker
     * feeding a field Jira reads as a user account is the kind of mismatch that only shows up as
     * a rejection at submit time.
     */
    private static Control controlFor(JiraMetadataGateway.FieldMeta field) {
        if (!field.allowedValues().isEmpty()) {
            return "array".equals(field.schemaType()) ? Control.MULTI_SELECT : Control.SELECT;
        }
        if ("date".equals(field.schemaType()) || "datetime".equals(field.schemaType())) {
            return Control.DATE;
        }
        if ("issuelink".equals(field.schemaType())) {
            return Control.ISSUE;
        }
        return switch (JiraFieldEncoding.forField(
                field.schemaType(), field.customType(), field.key())) {
            case RICH_TEXT -> Control.RICH_TEXT;
            case NUMBER -> Control.NUMBER;
            case STRING_ARRAY -> Control.LABELS;
            case ACCOUNT_OBJECT -> Control.USER;
            case ID_OBJECT, KEY_OBJECT, STRING -> Control.TEXT;
        };
    }

    /**
     * How faithfully jvault can render this field.
     *
     * <p>Stated rather than implied. A field jvault cannot render is shown read-only with a link
     * into Jira, which is a worse experience than Jira's own form — but a much better one than a
     * control that accepts input and drops it.
     */
    private static SupportLevel supportLevelOf(JiraMetadataGateway.FieldMeta field) {
        if (!field.settable()) {
            return SupportLevel.READ_ONLY;
        }
        // Settable in Jira, and jvault has nothing to collect it with. An attachment needs an
        // upload control and a restriction needs a role picker; rendering either as a text box
        // would accept what someone typed and drop it, which is the failure this whole enum
        // exists to prevent.
        if ("attachment".equals(field.key()) || "issuerestriction".equals(field.schemaType())) {
            return SupportLevel.READ_ONLY;
        }
        // The list of candidates is offered best-effort: which issue types may parent which
        // depends on a hierarchy configuration jvault cannot read, so Jira has the last word.
        if ("issuelink".equals(field.schemaType())) {
            return SupportLevel.DELEGATED_VALIDATION;
        }
        String type = field.customType();
        if (type == null) {
            return SupportLevel.REPRODUCED;
        }
        return switch (type) {
            case "textfield", "textarea", "url", "float", "datepicker", "datetime",
                 "select", "radiobuttons", "multiselect", "multicheckboxes",
                 "labels", "userpicker", "multiuserpicker", "grouppicker",
                 "multiversion", "version", "project", "cascadingselect" -> SupportLevel.REPRODUCED;
            // Ranking, sprint and Atlassian-team fields are maintained by Jira's own boards and
            // apps; a form that let someone set them by hand would be lying about the effect.
            case "gh-lexo-rank", "gh-sprint", "atlassian-team", "devsummarycf",
                 "jsw-issue-color", "issuerestriction" -> SupportLevel.READ_ONLY;
            default -> SupportLevel.DELEGATED_VALIDATION;
        };
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static PartType partTypeOf(String fieldKey) {
        return switch (fieldKey == null ? "" : fieldKey) {
            case "summary" -> PartType.SUMMARY;
            case "description" -> PartType.DESCRIPTION;
            case "environment" -> PartType.BODY;
            // Not a custom field, and the difference is load-bearing: it is how the form learns
            // whether uploaded documents are going to the vault or to Jira, which decides both
            // what the control says and whether it works at all.
            case "attachment" -> PartType.ATTACHMENT;
            default -> PartType.CUSTOM_FIELD;
        };
    }

    private ResponseEntity<?> authenticated(HttpServletRequest request,
                                            java.util.function.Function<Caller,
                                                    ResponseEntity<?>> handler) {
        Optional<Caller> caller = callers.resolve(request);
        if (caller.isEmpty()) {
            return ResponseEntity.status(401).body(ApiProblem.of(
                    HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required"));
        }
        return handler.apply(caller.get());
    }

    public enum SupportLevel {
        /** jvault renders and validates it locally. */
        REPRODUCED,
        /** Rendered best-effort; Jira's own rejection is mapped back to the field on submit. */
        DELEGATED_VALIDATION,
        /** Shown, not editable here. */
        READ_ONLY
    }

    /** The widget the form renders. Not a type — a type has many possible widgets. */
    public enum Control {
        TEXT, RICH_TEXT, NUMBER, DATE, SELECT, MULTI_SELECT, LABELS, USER, ISSUE
    }

    public record FormDefinition(String projectKey, String issueTypeId, List<FormField> fields) {
    }

    /**
     * @param jiraLocale the language Jira's own labels come back in, which is the service
     *                   account's rather than this caller's
     */
    /**
     * @param jiraBaseUrl where this deployment's issues live, so a client can link to one. The
     *                    link goes both ways: Jira carries a link to the vault, and the vault
     *                    carries a link back — somebody reading either should be one click from
     *                    the other
     */
    public record Identity(String user, String jiraAccount, String jiraLocale, String deployment,
                           String jiraBaseUrl) {
    }

    /**
     * @param placement      where this field's value will be stored, resolved before the user
     *                       types anything into it
     * @param classification the sensitivity it will be held at, when it leaves Jira
     * @param allowOverride  whether the user may push this field out of Jira themselves. Never
     *                       whether they may pull one back in — that is an administrator's call
     */
    public record FormField(String key,
                            String name,
                            boolean required,
                            String schemaType,
                            String customType,
                            List<JiraMetadataGateway.AllowedValue> allowedValues,
                            boolean hasMoreOptions,
                            String placement,
                            String classification,
                            boolean allowOverride,
                            String supportLevel,
                            String control) {
    }
}
