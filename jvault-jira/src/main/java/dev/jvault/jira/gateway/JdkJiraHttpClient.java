package dev.jvault.jira.gateway;

import dev.jvault.jira.deployment.JiraDeployment;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The one place bytes leave jvault for Jira.
 *
 * <p>Built on the JDK's own HTTP client: no extra dependency, HTTP/2 where the server offers it,
 * and — the reason that matters here — a clear separation between a connection that was never
 * established and a response that never arrived. That distinction decides whether a failure is
 * plainly retryable or genuinely ambiguous, and a client that blurs it would push the ambiguity
 * protocol into cases that do not need it.
 *
 * <p>Two timeouts, deliberately different. The connect timeout is short, because a Jira that
 * cannot be reached at all should fail fast and let the outbox reschedule. The request timeout is
 * long, because Jira legitimately takes seconds under load and cutting it short manufactures
 * ambiguity we would then have to resolve.
 */
public final class JdkJiraHttpClient implements JiraHttpClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final JiraDeployment deployment;
    private final Duration requestTimeout;

    public JdkJiraHttpClient(JiraDeployment deployment) {
        this(deployment, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, null);
    }

    /**
     * @param proxy a forward proxy, or {@code null}. An on-premises deployment reaching Jira
     *              Cloud goes through one; see the connectivity question in docs/decisions.md
     */
    public JdkJiraHttpClient(JiraDeployment deployment,
                             Duration connectTimeout,
                             Duration requestTimeout,
                             java.net.ProxySelector proxy) {
        this.deployment = Objects.requireNonNull(deployment, "deployment");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // Redirects are not followed. A redirect from a Jira endpoint is either a
                // misconfiguration or something worth noticing, and silently following one could
                // send an Authorization header to a host we did not intend.
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy != null) {
            builder.proxy(proxy);
        }
        this.httpClient = builder.build();
    }

    @Override
    public JiraHttpResponse send(JiraHttpRequest request) {
        URI uri = deployment.uri(request.path());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout);

        deployment.headers().forEach(builder::header);
        if (request.headers() != null) {
            request.headers().forEach(builder::header);
        }

        HttpRequest.BodyPublisher body = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body());
        builder.method(request.method(), body);

        try {
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new JiraHttpResponse(response.statusCode(), response.body(),
                    flatten(response.headers()));

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new JiraTransportException(e, reachedJira(e));
        }
    }

    /**
     * Whether the request can be assumed never to have reached Jira.
     *
     * <p>Only the failures that provably happened before anything was written count as "not
     * reached". Everything else — a read timeout, a reset mid-response, an unclassified I/O error
     * — is treated as possibly delivered, because assuming otherwise is how duplicate tickets get
     * created.
     */
    private static boolean reachedJira(Exception failure) {
        if (failure instanceof ConnectException || failure instanceof UnknownHostException) {
            return false;
        }
        if (failure instanceof java.net.http.HttpConnectTimeoutException) {
            return false;
        }
        return true;
    }

    private static Map<String, String> flatten(java.net.http.HttpHeaders headers) {
        var flat = new LinkedHashMap<String, String>();
        headers.map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                flat.put(name, values.get(0));
            }
        });
        return flat;
    }

    /** Carries whether the request may have taken effect, which the classifier needs. */
    public static final class JiraTransportException extends RuntimeException {

        private final boolean requestReachedJira;

        JiraTransportException(Throwable cause, boolean requestReachedJira) {
            super("Jira request failed before a response was received", cause);
            this.requestReachedJira = requestReachedJira;
        }

        public boolean requestReachedJira() {
            return requestReachedJira;
        }
    }
}
