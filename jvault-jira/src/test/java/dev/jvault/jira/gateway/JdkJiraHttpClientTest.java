package dev.jvault.jira.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jvault.jira.deployment.CloudDeployment;
import dev.jvault.jira.deployment.DataCenterDeployment;
import dev.jvault.jira.deployment.JiraCredentials;
import dev.jvault.jira.deployment.JiraDeployment;
import dev.jvault.jira.egress.JiraOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP client against a stub Jira.
 *
 * <p>A purpose-built stub on the JDK's own HTTP server rather than a mocking library: it needs no
 * dependency, no Docker, and it lets the tests assert the things that actually matter here —
 * which headers went out, and how a failure that produced no response is characterised.
 */
class JdkJiraHttpClientTest {

    private HttpServer server;
    private String baseUrl;
    private final List<RecordedRequest> received = new ArrayList<>();
    private final AtomicReference<StubResponse> nextResponse =
            new AtomicReference<>(new StubResponse(200, "{}", Map.of()));

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Nested
    @DisplayName("requests")
    class Requests {

        @Test
        @DisplayName("method, path and body reach Jira")
        void sendsTheRequest() {
            var client = new JdkJiraHttpClient(cloudAt(baseUrl));

            client.send(new JiraHttpClient.JiraHttpRequest(
                    "POST", "/rest/api/3/issue", "{\"fields\":{}}", Map.of()));

            assertThat(received).hasSize(1);
            assertThat(received.get(0).method()).isEqualTo("POST");
            assertThat(received.get(0).path()).isEqualTo("/rest/api/3/issue");
            assertThat(received.get(0).body()).isEqualTo("{\"fields\":{}}");
        }

        @Test
        @DisplayName("the deployment's authentication header is applied")
        void appliesCredentials() {
            var deployment = CloudDeployment.class.cast(cloudAt(baseUrl, "tok-123"));
            new JdkJiraHttpClient(deployment).send(get());

            assertThat(received.get(0).headers().get("Authorization")).isEqualTo("Bearer tok-123");
            assertThat(received.get(0).headers().get("Accept")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("per-request headers are added alongside the deployment's")
        void mergesPerRequestHeaders() {
            new JdkJiraHttpClient(cloudAt(baseUrl)).send(new JiraHttpClient.JiraHttpRequest(
                    "POST", "/rest/api/3/issue/X/attachments", "body",
                    Map.of("X-Atlassian-Token", "no-check")));

            // Attachment uploads are rejected outright without this header.
            assertThat(received.get(0).headers().get("X-Atlassian-Token")).isEqualTo("no-check");
        }

        @Test
        @DisplayName("the response status, body and headers come back")
        void returnsTheResponse() {
            nextResponse.set(new StubResponse(201, "{\"id\":\"10001\",\"key\":\"SEC-4471\"}",
                    Map.of("X-RateLimit-NearLimit", "true")));

            JiraHttpClient.JiraHttpResponse response =
                    new JdkJiraHttpClient(cloudAt(baseUrl)).send(get());

            assertThat(response.status()).isEqualTo(201);
            assertThat(response.body()).contains("SEC-4471");
            assertThat(JiraResponseClassifier.nearRateLimit(response.headers())).isTrue();
        }

        @Test
        @DisplayName("a throttle response carries its Retry-After through to the classifier")
        void throttleFlowsThrough() {
            nextResponse.set(new StubResponse(429, "", Map.of(
                    "Retry-After", "45", "RateLimit-Reason", "jira-burst-based")));

            JiraHttpClient.JiraHttpResponse response =
                    new JdkJiraHttpClient(cloudAt(baseUrl)).send(get());

            var result = JiraResponseClassifier.classify(
                    response.status(), response.headers(), null, null);

            assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(45));
            assertThat(JiraResponseClassifier.rateLimitReason(response.headers()))
                    .contains("jira-burst-based");
        }

        @Test
        @DisplayName("redirects are not followed")
        void doesNotFollowRedirects() {
            nextResponse.set(new StubResponse(302, "", Map.of("Location", "https://elsewhere.example")));

            JiraHttpClient.JiraHttpResponse response =
                    new JdkJiraHttpClient(cloudAt(baseUrl)).send(get());

            // Following one would send the Authorization header to a host we did not intend.
            assertThat(response.status()).isEqualTo(302);
            assertThat(received).hasSize(1);
        }
    }

    @Nested
    @DisplayName("transport failures")
    class Failures {

        @Test
        @DisplayName("a refused connection is reported as never having reached Jira")
        void connectionRefused() {
            // Port 1 on loopback: nothing is listening, so nothing can have taken effect.
            var unreachable = cloudAt("http://127.0.0.1:1");

            assertThatThrownBy(() -> new JdkJiraHttpClient(unreachable).send(get()))
                    .isInstanceOfSatisfying(JdkJiraHttpClient.JiraTransportException.class,
                            e -> assertThat(e.requestReachedJira()).isFalse());
        }

        @Test
        @DisplayName("a refused connection on a create is retryable, not ambiguous")
        void refusedConnectionIsRetryable() {
            var unreachable = cloudAt("http://127.0.0.1:1");

            try {
                new JdkJiraHttpClient(unreachable).send(get());
            } catch (JdkJiraHttpClient.JiraTransportException e) {
                var result = JiraResponseClassifier.classifyTransportFailure(
                        e.getCause(), e.requestReachedJira(), JiraOperation.CREATE_ISSUE);

                // Nothing was sent, so there is nothing to be ambiguous about.
                assertThat(result.outcome())
                        .isEqualTo(JiraWriteGateway.JiraWriteResult.Outcome.RETRYABLE);
            }
        }

        @Test
        @DisplayName("a response that never arrives is treated as possibly delivered")
        void readTimeoutIsAmbiguousForACreate() {
            nextResponse.set(new StubResponse(200, "{}", Map.of(), Duration.ofSeconds(5)));
            var client = new JdkJiraHttpClient(cloudAt(baseUrl),
                    Duration.ofSeconds(2), Duration.ofMillis(300), null);

            try {
                client.send(new JiraHttpClient.JiraHttpRequest(
                        "POST", "/rest/api/3/issue", "{}", Map.of()));
                org.junit.jupiter.api.Assertions.fail("expected a timeout");
            } catch (JdkJiraHttpClient.JiraTransportException e) {
                // The request was written; Jira may well have created the issue.
                assertThat(e.requestReachedJira()).isTrue();
                assertThat(JiraResponseClassifier.classifyTransportFailure(
                        e.getCause(), true, JiraOperation.CREATE_ISSUE).outcome())
                        .isEqualTo(JiraWriteGateway.JiraWriteResult.Outcome.AMBIGUOUS);
            }
        }
    }

    @Nested
    @DisplayName("deployment differences")
    class Deployments {

        @Test
        @DisplayName("Cloud addresses the Atlassian gateway by cloudId, and speaks ADF")
        void cloudShape() {
            var cloud = new CloudDeployment("prod", "cid-123", JiraCredentials.none());

            assertThat(cloud.uri("/rest/api/3/issue").toString())
                    .isEqualTo("https://api.atlassian.com/ex/jira/cid-123/rest/api/3/issue");
            assertThat(cloud.apiVersion()).isEqualTo("3");
            assertThat(cloud.richTextFormat()).isEqualTo(JiraDeployment.RichTextFormat.ADF);
        }

        @Test
        @DisplayName("Data Center addresses its own base URL, and speaks wiki markup")
        void dataCenterShape() {
            var dc = new DataCenterDeployment("dc", "https://jira.internal/", JiraCredentials.none());

            assertThat(dc.uri("/rest/api/2/issue").toString())
                    .isEqualTo("https://jira.internal/rest/api/2/issue");
            assertThat(dc.apiVersion()).isEqualTo("2");
            assertThat(dc.richTextFormat()).isEqualTo(JiraDeployment.RichTextFormat.WIKI_MARKUP);
        }

        @Test
        @DisplayName("capabilities are honest about what each deployment cannot do")
        void capabilitiesDiffer() {
            var cloud = new CloudDeployment("prod", "cid", JiraCredentials.none()).capabilities();
            var dc = new DataCenterDeployment("dc", "https://jira.internal",
                    JiraCredentials.none()).capabilities();

            // Section-level SPLIT is refused on wiki markup: marker fences break the moment
            // someone edits the description in Jira, which would commit sensitive text.
            assertThat(cloud.sectionLevelSplit()).isTrue();
            assertThat(dc.sectionLevelSplit()).isFalse();

            // PKCE is the reverse: Data Center has it, Cloud does not expose it.
            assertThat(cloud.pkceSupported()).isFalse();
            assertThat(dc.pkceSupported()).isTrue();

            // Neither can JQL-search entity properties without an app, which D4 rules out.
            assertThat(cloud.jqlSearchableProperties()).isFalse();
        }

        @Test
        @DisplayName("operation paths follow the deployment's API version")
        void operationPaths() {
            var cloud = new CloudDeployment("prod", "cid", JiraCredentials.none());
            var dc = new DataCenterDeployment("dc", "https://jira.internal", JiraCredentials.none());

            assertThat(cloud.pathFor(JiraOperation.CREATE_ISSUE, null))
                    .isEqualTo("/rest/api/3/issue");
            assertThat(dc.pathFor(JiraOperation.CREATE_ISSUE, null))
                    .isEqualTo("/rest/api/2/issue");
            assertThat(cloud.pathFor(JiraOperation.UPSERT_REMOTE_LINK, "SEC-1"))
                    .isEqualTo("/rest/api/3/issue/SEC-1/remotelink");
            assertThat(cloud.pathFor(JiraOperation.SET_PROPERTY, "SEC-1"))
                    .isEqualTo("/rest/api/3/issue/SEC-1/properties/jvault.origin");
        }
    }

    // --- stub --------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // HTTP header names are case-insensitive, and the JDK's HttpServer normalises them to
        // "X-atlassian-token" rather than preserving what was sent. Comparing case-sensitively
        // here would make the test wrong about HTTP rather than wrong about jvault.
        var headers = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        received.add(new RecordedRequest(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(), body, headers));

        StubResponse response = nextResponse.get();
        if (response.delay() != null) {
            try {
                Thread.sleep(response.delay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            exchange.getResponseBody().write(payload);
        }
        exchange.close();
    }

    private static JiraHttpClient.JiraHttpRequest get() {
        return new JiraHttpClient.JiraHttpRequest("GET", "/rest/api/3/myself", null, Map.of());
    }

    private static JiraDeployment cloudAt(String baseUrl) {
        return cloudAt(baseUrl, "tok");
    }

    private static JiraDeployment cloudAt(String baseUrl, String token) {
        return new CloudDeployment("test", "cid", JiraCredentials.bearer(() -> token), baseUrl);
    }

    private record RecordedRequest(String method, String path, String body,
                                   Map<String, String> headers) {
    }

    private record StubResponse(int status, String body, Map<String, String> headers,
                                Duration delay) {
        StubResponse(int status, String body, Map<String, String> headers) {
            this(status, body, headers, null);
        }
    }
}
