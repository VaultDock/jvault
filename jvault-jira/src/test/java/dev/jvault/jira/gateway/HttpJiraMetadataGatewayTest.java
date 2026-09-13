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
        var http = new RecordingClient("{\"issues\": []}");
        gateway(http).searchIssues("KAN", "O'Brien \" OR project = \"SECRET");

        String jql = queryParameter(http.paths.get(0), "jql");

        // The injected clause must be inside the literal, not beside it.
        assertThat(jql).doesNotContain("OR project = \"SECRET\"");
        assertThat(jql).contains("project = \"KAN\"");
        assertThat(jql).contains("issuetype not in subtaskIssueTypes()");
    }

    @Test
    @DisplayName("a backslash cannot escape the escaping")
    void backslashesAreEscapedFirst() {
        var http = new RecordingClient("{\"issues\": []}");
        gateway(http).searchIssues("KAN", "path\\\" OR x = \"y");

        String jql = queryParameter(http.paths.get(0), "jql");

        assertThat(jql).doesNotContain("OR x = \"y\"");
    }

    @Test
    @DisplayName("an empty query lists the project rather than searching for nothing")
    void emptyQueriesListTheProject() {
        var http = new RecordingClient("{\"issues\": []}");
        gateway(http).searchIssues("KAN", "");

        String jql = queryParameter(http.paths.get(0), "jql");

        // A picker opened with no text should show something, not an empty list.
        assertThat(jql).doesNotContain("summary ~");
        assertThat(jql).contains("ORDER BY updated DESC");
    }

    @Test
    @DisplayName("issues come back with the key, summary and type")
    void issuesAreMapped() {
        var http = new RecordingClient("""
                {"issues": [
                  {"key": "KAN-1",
                   "fields": {"summary": "Migrate payroll",
                              "issuetype": {"name": "Epic"}}}]}""");

        List<JiraMetadataGateway.IssueRef> issues = gateway(http).searchIssues("KAN", "pay");

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).key()).isEqualTo("KAN-1");
        assertThat(issues.get(0).summary()).isEqualTo("Migrate payroll");
        assertThat(issues.get(0).issueTypeName()).isEqualTo("Epic");
    }

    @Test
    @DisplayName("Jira's own error body is not forwarded")
    void errorBodiesAreNotForwarded() {
        var http = new RecordingClient("{\"errorMessages\":[\"the JQL was: project = SECRET\"]}", 400);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> gateway(http).searchIssues("KAN", "x"))
                .isInstanceOf(HttpJiraMetadataGateway.MetadataUnavailableException.class)
                .hasMessageNotContaining("SECRET");
    }

    private static HttpJiraMetadataGateway gateway(JiraHttpClient http) {
        var deployment = new CloudDeployment("jira-test", "unused",
                JiraCredentials.none(), "https://example.invalid");
        return new HttpJiraMetadataGateway(deployment, http);
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

    private static final class RecordingClient implements JiraHttpClient {
        private final List<String> paths = new ArrayList<>();
        private final String body;
        private final int status;

        RecordingClient(String body) {
            this(body, 200);
        }

        RecordingClient(String body, int status) {
            this.body = body;
            this.status = status;
        }

        @Override
        public JiraHttpResponse send(JiraHttpRequest request) {
            paths.add(request.path());
            return new JiraHttpResponse(status, body, Map.of());
        }
    }
}
