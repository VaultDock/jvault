package dev.jvault.jira.gateway;

/**
 * The raw HTTP transport to a Jira deployment.
 *
 * <p>Deliberately minimal and deliberately in this package. The architecture rule is that
 * <em>only</em> {@code dev.jvault.jira.gateway} may depend on this type, so that there is exactly
 * one place in the codebase where bytes can reach Jira. {@code EgressBoundaryArchTest} enforces
 * it; without that rule, "all Jira writes are sanitised" would be a convention, and conventions
 * do not survive the fourth refactor.
 */
public interface JiraHttpClient {

    JiraHttpResponse send(JiraHttpRequest request);

    record JiraHttpRequest(String method, String path, String body, java.util.Map<String, String> headers) {
    }

    record JiraHttpResponse(int status, String body, java.util.Map<String, String> headers) {
    }
}
