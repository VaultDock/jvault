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

    /** Same reasoning: a picker shows a page, and more typing beats more scrolling. */
    private static final int MAX_ISSUES = 20;

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
                    type.path("description").asText(null),
                    // Absent on older deployments; a subtask is -1 and everything else 0, which
                    // is the shape Jira had before epics were part of the hierarchy.
                    type.path("hierarchyLevel").asInt(
                            type.path("subtask").asBoolean(false) ? -1 : 0)));
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

    @Override
    public Map<String, String> displayNamesOf(java.util.Collection<String> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }

        var query = new StringBuilder("?maxResults=" + MAX_USERS);
        for (String accountId : accountIds.stream().distinct().limit(MAX_USERS).toList()) {
            query.append("&accountId=").append(encode(accountId));
        }

        JsonNode body;
        try {
            body = get("/rest/api/" + deployment.apiVersion() + "/user/bulk" + query);
        } catch (MetadataUnavailableException e) {
            // Names are a courtesy. A ticket that cannot be read because Jira is slow to answer
            // a lookup would be a worse trade than showing the ids.
            return Map.of();
        }

        var names = new java.util.LinkedHashMap<String, String>();
        for (JsonNode user : body.path("values")) {
            String accountId = user.path("accountId").asText(null);
            String displayName = user.path("displayName").asText(null);
            if (accountId != null && displayName != null) {
                names.put(accountId, displayName);
            }
        }
        return Map.copyOf(names);
    }

    @Override
    public List<IssueRef> searchIssues(String projectKey, String query, String childIssueTypeId) {
        List<IssueType> types = issueTypes(projectKey);
        String childId = childIssueTypeId == null ? "" : childIssueTypeId;

        int childLevel = types.stream()
                .filter(type -> childId.equals(type.id()))
                .mapToInt(IssueType::hierarchyLevel)
                .findFirst()
                .orElse(0);

        List<String> parentTypes = types.stream()
                .filter(type -> type.hierarchyLevel() == childLevel + 1)
                .map(IssueType::name)
                .toList();

        if (parentTypes.isEmpty()) {
            // Nothing sits above this type, so nothing can be its parent. An empty list is the
            // honest answer; a list of things Jira will refuse is not.
            return List.of();
        }
        return search(projectKey, query, parentTypes);
    }

    private List<IssueRef> search(String projectKey, String query, List<String> parentTypes) {
        // Quoted and escaped: a project key comes from configuration, but the query is whatever
        // somebody typed, and a stray quote in a JQL string is a syntax error at best.
        var jql = new StringBuilder("project = \"").append(jqlEscape(projectKey))
                .append("\" AND issuetype in (");
        for (int i = 0; i < parentTypes.size(); i++) {
            jql.append(i == 0 ? "" : ", ").append('"')
                    .append(jqlEscape(parentTypes.get(i))).append('"');
        }
        jql.append(')');

        if (query != null && !query.isBlank()) {
            String term = jqlEscape(query.trim());
            jql.append(" AND (summary ~ \"").append(term).append("*\"")
                    .append(" OR key = \"").append(term).append("\")");
        }
        jql.append(" ORDER BY updated DESC");

        JsonNode body = get("/rest/api/" + deployment.apiVersion() + "/search/jql"
                + "?jql=" + encode(jql.toString())
                + "&fields=" + encode("summary,issuetype")
                + "&maxResults=" + MAX_ISSUES);

        var issues = new ArrayList<IssueRef>();
        for (JsonNode issue : body.path("issues")) {
            issues.add(new IssueRef(
                    issue.path("key").asText(null),
                    issue.path("fields").path("summary").asText(null),
                    issue.path("fields").path("issuetype").path("name").asText(null)));
        }
        return List.copyOf(issues);
    }

    /**
     * Neutralises the characters that end a JQL string literal.
     *
     * <p>A backslash first, or escaping the quote would be undone by escaping the escape.
     */
    private static String jqlEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
                schema.path("items").asText(null),
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
