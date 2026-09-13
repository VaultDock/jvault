package dev.jvault.jira.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.jira.deployment.JiraDeployment;
import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.jira.egress.JiraSafePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The real gateway: maps a checked payload to HTTP, sends it, and classifies what came back.
 *
 * <p>Three collaborators, each with one job — {@link JiraRequestMapper} builds the request,
 * {@link JiraHttpClient} sends it, {@link JiraResponseClassifier} decides what the result means
 * for the outbox. Keeping them apart is what lets the retry policy be unit-tested without a
 * socket and the request shape be unit-tested without a server.
 *
 * <p>It accepts only a {@link JiraSafePayload}, so there is no route from unchecked values to
 * Jira through this class.
 */
public final class HttpJiraWriteGateway implements JiraWriteGateway {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JiraHttpClient http;
    private final JiraRequestMapper mapper;
    private final Function<String, String> issueKeyForLane;

    public HttpJiraWriteGateway(JiraDeployment deployment, JiraHttpClient http) {
        this(deployment, http, HttpJiraWriteGateway::issueFromLane);
    }

    /**
     * @param issueKeyForLane resolves an outbox lane to the Jira issue it targets. The default
     *                        reads it from the lane name, which the dispatcher sets to
     *                        {@code issue:<id>} once the issue exists
     */
    public HttpJiraWriteGateway(JiraDeployment deployment,
                                JiraHttpClient http,
                                Function<String, String> issueKeyForLane) {
        Objects.requireNonNull(deployment, "deployment");
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = new JiraRequestMapper(deployment);
        this.issueKeyForLane = Objects.requireNonNull(issueKeyForLane, "issueKeyForLane");
    }

    @Override
    public JiraWriteResult execute(JiraSafePayload payload) {
        Objects.requireNonNull(payload, "payload");

        String issue = payload.operation() == JiraOperation.CREATE_ISSUE
                ? null
                : issueKeyForLane.apply(payload.issueLane());

        JiraHttpClient.JiraHttpRequest request;
        try {
            request = mapper.toRequest(payload, issue);
        } catch (RuntimeException e) {
            // A payload we cannot even render is not going to render on a retry either.
            return JiraWriteResult.rejected("JVAULT_REQUEST_MAPPING_FAILED");
        }

        JiraHttpClient.JiraHttpResponse response;
        try {
            response = http.send(request);
        } catch (JdkJiraHttpClient.JiraTransportException e) {
            return JiraResponseClassifier.classifyTransportFailure(
                    e.getCause(), e.requestReachedJira(), payload.operation());
        }

        JiraWriteResult result = JiraResponseClassifier.classify(
                response.status(), response.headers(), null, null);

        if (result.outcome() == JiraWriteResult.Outcome.SUCCEEDED
                && payload.operation() == JiraOperation.CREATE_ISSUE) {
            Created created = parseCreated(response.body());
            return new JiraWriteResult(created.id(), created.key(),
                    JiraWriteResult.Outcome.SUCCEEDED, null, null);
        }

        if (result.outcome() == JiraWriteResult.Outcome.REJECTED) {
            // Jira's rejection usually names the offending fields. Those names are useful to an
            // operator and are not content — but the *values* are, so only the keys are kept.
            String detail = rejectedFields(response.body());
            if (!detail.isEmpty()) {
                return new JiraWriteResult(null, null, JiraWriteResult.Outcome.REJECTED,
                        result.errorCode() + ":" + detail, null);
            }
        }
        return result;
    }

    private static Created parseCreated(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            return new Created(text(node, "id"), text(node, "key"));
        } catch (Exception e) {
            // The write succeeded; we simply cannot read which issue it produced. Reporting
            // success without an id would leave the ticket unable to link itself, so treat it as
            // ambiguous and let the reconciler find the issue.
            return new Created(null, null);
        }
    }

    /**
     * Field names from a Jira error body, never field values.
     *
     * <p>Jira returns {@code {"errorMessages": [...], "errors": {"customfield_10010": "..."}}}.
     * The keys tell an operator which field to look at; the messages can quote the value that was
     * submitted, which is exactly what must not travel into an error code, a log line or a
     * dead-letter record.
     */
    private static String rejectedFields(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            JsonNode errors = node.get("errors");
            if (errors == null || !errors.isObject()) {
                return "";
            }
            List<String> keys = new ArrayList<>();
            errors.fieldNames().forEachRemaining(keys::add);
            return String.join(",", keys);
        } catch (Exception e) {
            return "";
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** {@code issue:10001} -> {@code 10001}; anything else is not an issue yet. */
    private static String issueFromLane(String lane) {
        return lane != null && lane.startsWith("issue:") ? lane.substring("issue:".length()) : null;
    }

    private record Created(String id, String key) {
    }
}
