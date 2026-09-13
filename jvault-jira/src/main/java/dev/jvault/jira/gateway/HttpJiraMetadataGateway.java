package dev.jvault.jira.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.jira.deployment.JiraDeployment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Create metadata over the real Jira API.
 *
 * <p>Uses the replacement createmeta endpoints, never the deprecated aggregate form. That form
 * still answers 200 on a live site (docs/00-verified-capabilities.md 0.5), which makes it tempting
 * and would make the eventual removal an outage rather than a deprecation notice.
 *
 * <p>Option lists are capped. Jira happily returns every value of a large select, and a form that
 * renders ten thousand options is not a dropdown — it is a lookup that has not been built yet. The
 * cap is reported so the client can say so rather than silently showing a truncated list.
 */
public final class HttpJiraMetadataGateway implements JiraMetadataGateway {

    /** Beyond this an option list needs a search endpoint, not a longer response. */
    private static final int MAX_OPTIONS = 100;

    /** A picker shows a page, not a directory. Anything longer means typing more, not scrolling. */
    private static final int MAX_USERS = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JiraDeployment deployment;
    private final JiraHttpClient http;

    public HttpJiraMetadataGateway(JiraDeployment deployment, JiraHttpClient http) {
        this.deployment = Objects.requireNonNull(deployment, "deployment");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public List<Project> projects() {
        JsonNode body = get("/rest/api/" + deployment.apiVersion()
                + "/project/search?maxResults=100&action=create");

        var projects = new ArrayList<Project>();
        for (JsonNode project : body.path("values")) {
            projects.add(new Project(
                    project.path("id").asText(null),
                    project.path("key").asText(null),
                    project.path("name").asText(null),
                    project.path("style").asText(null)));
        }
        return List.copyOf(projects);
    }

    @Override
    public List<IssueType> issueTypes(String projectKey) {
        JsonNode body = get("/rest/api/" + deployment.apiVersion()
                + "/issue/createmeta/" + encode(projectKey) + "/issuetypes?maxResults=100");

        var types = new ArrayList<IssueType>();
        for (JsonNode type : valuesOf(body, "issueTypes")) {
            types.add(new IssueType(
                    type.path("id").asText(null),
                    type.path("name").asText(null),
                    type.path("subtask").asBoolean(false),
                    type.path("description").asText(null)));
        }
        return List.copyOf(types);
    }

    @Override
    public List<FieldMeta> fields(String projectKey, String issueTypeId) {
        JsonNode body = get("/rest/api/" + deployment.apiVersion()
                + "/issue/createmeta/" + encode(projectKey)
                + "/issuetypes/" + encode(issueTypeId) + "?maxResults=200");

        var fields = new ArrayList<FieldMeta>();
        for (JsonNode field : valuesOf(body, "fields")) {
            fields.add(toFieldMeta(field));
        }
        return List.copyOf(fields);
    }

    @Override
    public CurrentUser currentUser() {
        JsonNode body = get("/rest/api/" + deployment.apiVersion() + "/myself");
        return new CurrentUser(
                body.path("accountId").asText(null),
                body.path("displayName").asText(null),
                body.path("locale").asText(null));
    }

    @Override
    public List<UserRef> searchUsers(String projectKey, String query, boolean assignable) {
        String path = assignable
                ? "/rest/api/" + deployment.apiVersion() + "/user/assignable/search?project="
                        + encode(projectKey)
                : "/rest/api/" + deployment.apiVersion() + "/user/search?";

        JsonNode body = get(path + "&query=" + encode(query == null ? "" : query)
                + "&maxResults=" + MAX_USERS);

        var users = new ArrayList<UserRef>();
        for (JsonNode user : body) {
            // App and customer accounts show up in these results and mean nothing on a create
            // screen; offering them is how someone assigns an issue to a bot.
            if (!"atlassian".equals(user.path("accountType").asText("atlassian"))) {
                continue;
            }
            users.add(new UserRef(
                    user.path("accountId").asText(null),
                    user.path("displayName").asText(null),
                    // Absent unless the account shares it, which most do not.
                    user.path("emailAddress").asText(null),
                    user.path("active").asBoolean(true)));
        }
        return List.copyOf(users);
    }

    private static FieldMeta toFieldMeta(JsonNode field) {
        JsonNode schema = field.path("schema");
        String custom = schema.path("custom").asText(null);

        var allowed = new ArrayList<AllowedValue>();
        JsonNode allowedValues = field.path("allowedValues");
        boolean hasMore = allowedValues.size() > MAX_OPTIONS;
        for (int i = 0; i < Math.min(allowedValues.size(), MAX_OPTIONS); i++) {
            JsonNode option = allowedValues.get(i);
            allowed.add(new AllowedValue(
                    option.path("id").asText(null),
                    // Jira uses different labels depending on the field type, and a select whose
                    // options render as "null" is worse than one that renders as its id.
                    firstNonBlank(option, "value", "name", "displayName", "id")));
        }

        // Absent and empty are different answers: empty is Jira refusing the field, absent is a
        // deployment that does not report operations at all. Only the first should lock a control.
        JsonNode operationsNode = field.path("operations");
        List<String> operations = null;
        if (operationsNode.isArray()) {
            var found = new ArrayList<String>();
            operationsNode.forEach(op -> found.add(op.asText()));
            operations = found;
        }

        return new FieldMeta(
                firstNonBlank(field, "fieldId", "key"),
                field.path("name").asText(null),
                field.path("required").asBoolean(false),
                schema.path("type").asText(null),
                custom == null ? null : custom.substring(custom.lastIndexOf(':') + 1),
                allowed, hasMore, operations);
    }

    /** Jira returns these collections under different keys depending on the endpoint shape. */
    private static Iterable<JsonNode> valuesOf(JsonNode body, String preferred) {
        JsonNode node = body.path(preferred);
        if (node.isMissingNode() || node.isNull()) {
            node = body.path("values");
        }
        if (node.isObject()) {
            // The fields collection is sometimes an object keyed by field id.
            var list = new ArrayList<JsonNode>();
            node.forEach(list::add);
            return list;
        }
        return node;
    }

    private JsonNode get(String path) {
        JiraHttpClient.JiraHttpResponse response =
                http.send(new JiraHttpClient.JiraHttpRequest("GET", path, null, Map.of()));

        if (response.status() != 200) {
            // The caller turns this into a problem document. Jira's own body is not forwarded:
            // it can quote the request, and a metadata failure is not worth that risk.
            throw new MetadataUnavailableException(
                    "Jira metadata request failed with status " + response.status());
        }
        try {
            return JSON.readTree(response.body());
        } catch (Exception e) {
            throw new MetadataUnavailableException("could not read the Jira metadata response");
        }
    }

    private static String firstNonBlank(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static class MetadataUnavailableException extends RuntimeException {
        public MetadataUnavailableException(String message) {
            super(message);
        }
    }
}
