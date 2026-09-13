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

    public MetadataController(JiraMetadataGateway metadata,
                              PolicySet policies,
                              CallerResolver callers,
                              String deploymentId) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId");
    }

    @GetMapping("/projects")
    public ResponseEntity<?> projects(HttpServletRequest request) {
        return authenticated(request, caller -> ResponseEntity.ok(metadata.projects()));
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

    private static PartType partTypeOf(String fieldKey) {
        return switch (fieldKey == null ? "" : fieldKey) {
            case "summary" -> PartType.SUMMARY;
            case "description" -> PartType.DESCRIPTION;
            case "environment" -> PartType.BODY;
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
        TEXT, RICH_TEXT, NUMBER, DATE, SELECT, MULTI_SELECT, LABELS, USER
    }

    public record FormDefinition(String projectKey, String issueTypeId, List<FormField> fields) {
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
