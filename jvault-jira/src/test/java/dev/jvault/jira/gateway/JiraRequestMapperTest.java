package dev.jvault.jira.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.domain.common.Classification;
import dev.jvault.jira.deployment.CloudDeployment;
import dev.jvault.jira.deployment.DataCenterDeployment;
import dev.jvault.jira.deployment.JiraCredentials;
import dev.jvault.jira.deployment.JiraDeployment;
import dev.jvault.jira.egress.EgressGuard;
import dev.jvault.jira.egress.JiraFieldEncoding;
import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.jira.egress.JiraSafePayload;
import dev.jvault.jira.egress.JiraWriteRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request bodies jvault actually sends.
 *
 * <p>These assert the JSON shape rather than round-tripping through Jira, because the shape is
 * where the deployment differences live and because getting it wrong produces a 400 whose message
 * quotes the submitted value — which is the last thing that should end up in an error path.
 */
class JiraRequestMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JiraDeployment cloud =
            new CloudDeployment("cloud", "cid", JiraCredentials.none());
    private final JiraDeployment dataCenter =
            new DataCenterDeployment("dc", "https://jira.internal", JiraCredentials.none());

    @Nested
    @DisplayName("creating an issue")
    class CreateIssue {

        @Test
        @DisplayName("structural fields become the objects Jira expects, not strings")
        void structuralFieldsAreEncoded() throws Exception {
            JsonNode body = bodyOf(cloud, createPayload());

            // A project is {"key": ...} and an issue type {"id": ...}. Sending either as a bare
            // string is a 400, and guessing from the value would break on the first field whose
            // text happens to look like an id.
            assertThat(body.at("/fields/project/key").asText()).isEqualTo("SEC");
            assertThat(body.at("/fields/issuetype/id").asText()).isEqualTo("10004");
            assertThat(body.at("/fields/summary").asText()).isEqualTo("An incident");
        }

        @Test
        @DisplayName("the origin property travels with the create")
        void propertiesRideWithTheCreate() throws Exception {
            JsonNode body = bodyOf(cloud, createPayload());

            // Verified against a live site (docs 0.9). This is what closes the window in which a
            // create succeeds and its follow-up property write does not.
            assertThat(body.at("/properties/0/key").asText()).isEqualTo("jvault.origin");
            assertThat(body.at("/properties/0/value/correlationId").asText()).isEqualTo("corr-1");
        }

        @Test
        @DisplayName("routing keys are not sent as Jira fields")
        void routingKeysAreNotFields() throws Exception {
            JsonNode body = bodyOf(cloud, createPayload());

            assertThat(body.at("/fields").has("globalId")).isFalse();
            assertThat(body.at("/fields").has("url")).isFalse();
        }

        @Test
        @DisplayName("labels become an array, not a comma-separated string")
        void labelsBecomeAnArray() throws Exception {
            JiraSafePayload payload = sanitise(request(JiraOperation.CREATE_ISSUE)
                    .field("summary", "x")
                    .field("labels", "security, ci , egress", JiraFieldEncoding.STRING_ARRAY));

            JsonNode labels = bodyOf(cloud, payload).at("/fields/labels");

            assertThat(labels.isArray()).isTrue();
            assertThat(labels).hasSize(3);
            assertThat(labels.get(1).asText()).isEqualTo("ci");
        }

        @Test
        @DisplayName("a value that will not parse as a number is sent as text, not rejected here")
        void unparseableNumberFallsBackToText() throws Exception {
            JiraSafePayload payload = sanitise(request(JiraOperation.CREATE_ISSUE)
                    .field("summary", "x")
                    .field("customfield_1", "not-a-number", JiraFieldEncoding.NUMBER));

            // Letting Jira produce a field-level error naming the field is more useful to an
            // operator than a mapping exception that names nothing.
            assertThat(bodyOf(cloud, payload).at("/fields/customfield_1").asText())
                    .isEqualTo("not-a-number");
        }
    }

    @Nested
    @DisplayName("rich text differs by deployment")
    class RichText {

        @Test
        @DisplayName("Cloud gets an ADF document")
        void cloudGetsAdf() throws Exception {
            JiraSafePayload payload = sanitise(request(JiraOperation.CREATE_ISSUE)
                    .field("summary", "x")
                    .field("description", "First line.\n\nSecond block.",
                            JiraFieldEncoding.RICH_TEXT));

            JsonNode description = bodyOf(cloud, payload).at("/fields/description");

            assertThat(description.get("type").asText()).isEqualTo("doc");
            assertThat(description.get("version").asInt()).isEqualTo(1);
            assertThat(description.get("content")).hasSize(2);
            assertThat(description.at("/content/0/content/0/text").asText())
                    .isEqualTo("First line.");
        }

        @Test
        @DisplayName("a web address in the text arrives as a link somebody can follow")
        void urlsBecomeLinks() throws Exception {
            JiraSafePayload payload = sanitise(request(JiraOperation.CREATE_ISSUE)
                    .field("summary", "x")
                    .field("description",
                            "=== SECURED ===\nRead it at https://jvault.example.com/t/abc123.",
                            JiraFieldEncoding.RICH_TEXT));

            JsonNode inline = bodyOf(cloud, payload).at("/fields/description/content/0/content");

            // The surrogate exists to say where the content went, which it does badly if the
            // address is text the reader has to retype.
            JsonNode link = inline.get(3);
            assertThat(link.at("/marks/0/type").asText()).isEqualTo("link");
            // The full stop ends the sentence; it is not part of the address.
            assertThat(link.at("/marks/0/attrs/href").asText())
                    .isEqualTo("https://jvault.example.com/t/abc123");
            assertThat(link.get("text").asText()).isEqualTo("https://jvault.example.com/t/abc123");
            assertThat(inline.get(4).get("text").asText()).isEqualTo(".");
        }

        @Test
        @DisplayName("a single newline is a hard break, not a new paragraph")
        void singleNewlineIsAHardBreak() throws Exception {
            JiraSafePayload payload = sanitise(request(JiraOperation.CREATE_ISSUE)
                    .field("summary", "x")
                    .field("description", "Line one\nLine two", JiraFieldEncoding.RICH_TEXT));

            JsonNode description = bodyOf(cloud, payload).at("/fields/description");

            // Which is what a user who pressed Shift+Enter meant.
            assertThat(description.get("content")).hasSize(1);
            assertThat(description.at("/content/0/content/1/type").asText()).isEqualTo("hardBreak");
        }

        @Test
        @DisplayName("empty text still produces a valid document")
        void emptyTextIsStillValid() throws Exception {
            JiraSafePayload payload = sanitise(request(JiraOperation.CREATE_ISSUE)
                    .field("summary", "x")
                    .field("description", "", JiraFieldEncoding.RICH_TEXT));

            // A malformed document is a 400, and a 400 here would strand the ticket in the outbox.
            JsonNode description = bodyOf(cloud, payload).at("/fields/description");
            assertThat(description.get("type").asText()).isEqualTo("doc");
            assertThat(description.get("content")).hasSize(1);
        }

        @Test
        @DisplayName("Data Center gets wiki markup as a plain string")
        void dataCenterGetsWikiMarkup() throws Exception {
            JiraSafePayload payload = sanitise(request(JiraOperation.CREATE_ISSUE)
                    .field("summary", "x")
                    .field("description", "First line.\n\nSecond block.",
                            JiraFieldEncoding.RICH_TEXT));

            JsonNode description = bodyOf(dataCenter, payload).at("/fields/description");

            assertThat(description.isTextual()).isTrue();
            assertThat(description.asText()).isEqualTo("First line.\n\nSecond block.");
        }
    }

    @Nested
    @DisplayName("paths follow the deployment's API version")
    class Paths {

        @Test
        @DisplayName("Cloud uses v3, Data Center uses v2")
        void apiVersions() {
            var cloudRequest = new JiraRequestMapper(cloud)
                    .toRequest(sanitise(request(JiraOperation.CREATE_ISSUE).field("summary", "x")), null);
            var dcRequest = new JiraRequestMapper(dataCenter)
                    .toRequest(sanitise(request(JiraOperation.CREATE_ISSUE).field("summary", "x")), null);

            assertThat(cloudRequest.path()).isEqualTo("/rest/api/3/issue");
            assertThat(dcRequest.path()).isEqualTo("/rest/api/2/issue");
            assertThat(cloudRequest.method()).isEqualTo("POST");
        }

        @Test
        @DisplayName("a remote link targets the issue and carries its globalId")
        void remoteLinkShape() throws Exception {
            JiraSafePayload payload = sanitise(request(JiraOperation.UPSERT_REMOTE_LINK)
                    .issueLane("issue:10001")
                    .field("globalId", "jvault:content:abc")
                    .field("url", "https://jvault.example.com/c/abc")
                    .field("title", "attachment (512 bytes, application/pdf)"));

            var httpRequest = new JiraRequestMapper(cloud).toRequest(payload, "10001");
            JsonNode body = JSON.readTree(httpRequest.body());

            assertThat(httpRequest.path()).isEqualTo("/rest/api/3/issue/10001/remotelink");
            assertThat(body.get("globalId").asText()).isEqualTo("jvault:content:abc");
            assertThat(body.at("/object/url").asText()).isEqualTo("https://jvault.example.com/c/abc");
            // The title is built from part type, size and media type — never the filename.
            assertThat(body.at("/object/title").asText()).doesNotContain(".pdf\"");
        }

        @Test
        @DisplayName("a comment body is rich text for the deployment")
        void commentShape() throws Exception {
            JiraSafePayload payload = sanitise(request(JiraOperation.ADD_COMMENT)
                    .issueLane("issue:10001")
                    .field("body", "Stored in jvault: https://jvault.example.com/c/abc"));

            var httpRequest = new JiraRequestMapper(cloud).toRequest(payload, "10001");
            JsonNode body = JSON.readTree(httpRequest.body());

            assertThat(httpRequest.path()).isEqualTo("/rest/api/3/issue/10001/comment");
            assertThat(body.at("/body/type").asText()).isEqualTo("doc");
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static JiraWriteRequest.Builder request(JiraOperation operation) {
        return JiraWriteRequest.builder(operation, "ticket-1")
                .classification(Classification.INTERNAL);
    }

    private static JiraSafePayload createPayload() {
        return sanitise(request(JiraOperation.CREATE_ISSUE)
                .field("project", "SEC", JiraFieldEncoding.KEY_OBJECT)
                .field("issuetype", "10004", JiraFieldEncoding.ID_OBJECT)
                .field("summary", "An incident")
                .field("globalId", "should-not-be-a-field")
                .field("url", "should-not-be-a-field")
                .property("jvault.origin", "{\"ticketRef\":\"t1\",\"correlationId\":\"corr-1\"}"));
    }

    /** Payloads only exist on the far side of the guard, so tests build them the same way. */
    private static JiraSafePayload sanitise(JiraWriteRequest.Builder builder) {
        return EgressGuard.withDefaults().sanitise(builder.build());
    }

    private static JsonNode bodyOf(JiraDeployment deployment, JiraSafePayload payload)
            throws Exception {
        return JSON.readTree(new JiraRequestMapper(deployment).toRequest(payload, null).body());
    }
}
