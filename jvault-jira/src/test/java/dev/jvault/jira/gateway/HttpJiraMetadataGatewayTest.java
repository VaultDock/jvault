package dev.jvault.jira.gateway;

import dev.jvault.jira.deployment.CloudDeployment;
import dev.jvault.jira.deployment.JiraCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The requests the metadata gateway builds.
 *
 * <p>Mostly uninteresting except for the search, which puts a string somebody typed into a JQL
 * query. A JQL string literal ends at the first unescaped quote, so a search term containing one
 * is a syntax error at best.
 */
class HttpJiraMetadataGatewayTest {

    @Test
    @DisplayName("a quote in a search term does not end the JQL string it sits in")
    void searchTermsAreEscaped() {
        var http = new RecordingClient(ISSUE_TYPES, "{\"issues\": []}");
        gateway(http).searchIssues("KAN", "O'Brien \" OR project = \"SECRET", "10004");

        String jql = queryParameter(jqlPathOf(http), "jql");

        // The injected clause must be inside the literal, not beside it.
        assertThat(jql).doesNotContain("OR project = \"SECRET\"");
        assertThat(jql).contains("project = \"KAN\"");
        // A parent sits one level above a task, so only epics are candidates.
        assertThat(jql).contains("issuetype in (\"Epic\")");
    }

    @Test
    @DisplayName("a backslash cannot escape the escaping")
    void backslashesAreEscapedFirst() {
        var http = new RecordingClient(ISSUE_TYPES, "{\"issues\": []}");
        gateway(http).searchIssues("KAN", "path\\\" OR x = \"y", "10004");

        String jql = queryParameter(jqlPathOf(http), "jql");

        assertThat(jql).doesNotContain("OR x = \"y\"");
    }

    @Test
    @DisplayName("an empty query lists the project rather than searching for nothing")
    void emptyQueriesListTheProject() {
        var http = new RecordingClient(ISSUE_TYPES, "{\"issues\": []}");
        gateway(http).searchIssues("KAN", "", "10004");

        String jql = queryParameter(jqlPathOf(http), "jql");

        // A picker opened with no text should show something, not an empty list.
        assertThat(jql).doesNotContain("summary ~");
        assertThat(jql).contains("ORDER BY updated DESC");
    }

    @Test
    @DisplayName("issues come back with the key, summary and type")
    void issuesAreMapped() {
        var http = new RecordingClient(ISSUE_TYPES, """
                {"issues": [
                  {"key": "KAN-1",
                   "fields": {"summary": "Migrate payroll",
                              "issuetype": {"name": "Epic"}}}]}""");

        List<JiraMetadataGateway.IssueRef> issues = gateway(http).searchIssues("KAN", "pay", "10004");

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).key()).isEqualTo("KAN-1");
        assertThat(issues.get(0).summary()).isEqualTo("Migrate payroll");
        assertThat(issues.get(0).issueTypeName()).isEqualTo("Epic");
    }

    @Test
    @DisplayName("Jira's own error body is not forwarded")
    void errorBodiesAreNotForwarded() {
        var http = new RecordingClient(ISSUE_TYPES, "{\"errorMessages\":[\"the JQL was: project = SECRET\"]}", 400);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> gateway(http).searchIssues("KAN", "x", "10004"))
                .isInstanceOf(HttpJiraMetadataGateway.MetadataUnavailableException.class)
                .hasMessageNotContaining("SECRET");
    }

    private static HttpJiraMetadataGateway gateway(JiraHttpClient http) {
        var deployment = new CloudDeployment("jira-test", "unused",
                JiraCredentials.none(), "https://example.invalid");
        return new HttpJiraMetadataGateway(deployment, http);
    }

    /** The search request, not the issue-type lookup that precedes it. */
    private static String jqlPathOf(RecordingClient http) {
        return http.paths.stream().filter(path -> path.contains("jql="))
                .findFirst().orElseThrow(() -> new AssertionError("no search was issued"));
    }

    private static String queryParameter(String path, String name) {
        String query = path.substring(path.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (pair.substring(0, equals).equals(name)) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no " + name + " parameter in " + path);
    }

    /** What a project's issue types look like; the search resolves the hierarchy before asking. */
    private static final String ISSUE_TYPES = """
            {"issueTypes": [
              {"id": "10001", "name": "Epic", "subtask": false, "hierarchyLevel": 1},
              {"id": "10004", "name": "Task", "subtask": false, "hierarchyLevel": 0},
              {"id": "10002", "name": "Subtask", "subtask": true, "hierarchyLevel": -1}]}""";

    private static final class RecordingClient implements JiraHttpClient {
        private final List<String> paths = new ArrayList<>();
        private final String issueTypes;
        private final String body;
        private final int status;

        RecordingClient(String issueTypes, String body) {
            this(issueTypes, body, 200);
        }

        RecordingClient(String issueTypes, String body, int status) {
            this.issueTypes = issueTypes;
            this.body = body;
            this.status = status;
        }

        @Override
        public JiraHttpResponse send(JiraHttpRequest request) {
            paths.add(request.path());
            if (request.path().contains("/issuetypes")) {
                // Always 200: the hierarchy lookup is not what these tests are about.
                return new JiraHttpResponse(200, issueTypes, Map.of());
            }
            return new JiraHttpResponse(status, body, Map.of());
        }
    }
}
