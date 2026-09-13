package dev.jvault.jira.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.domain.common.SensitiveValue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Checks a manually supplied Jira credential before jvault agrees to keep it.
 *
 * <p>An invalid credential rejected at entry is a typo. The same credential accepted and
 * discovered broken at first use is an incident at three in the morning, so this asks Jira
 * before anything is stored (docs/10-authentication.md 10.6).
 *
 * <p>It also asks whether the account is a Jira administrator. jvault declines those: a
 * credential with administrator rights can read every project, which makes the intersection with
 * jvault's own grants meaningless for the person holding it.
 */
public final class JiraCredentialValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * @param siteUrl the Jira site, for example {@code https://acme.atlassian.net}
     * @param email   the account the token belongs to; half of the basic-auth credential
     */
    public Result validate(String siteUrl, String email, SensitiveValue token) {
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (email + ":" + token.reveal()).getBytes(StandardCharsets.UTF_8));

        JsonNode me;
        try {
            me = get(siteUrl + "/rest/api/3/myself", authorization);
        } catch (CredentialRejected e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CredentialRejected("UNREACHABLE");
        }

        String accountId = me.path("accountId").asText(null);
        if (accountId == null) {
            throw new CredentialRejected("NO_ACCOUNT");
        }

        boolean administrator;
        try {
            JsonNode permissions = get(
                    siteUrl + "/rest/api/3/mypermissions?permissions=ADMINISTER", authorization);
            administrator = permissions.path("permissions").path("ADMINISTER")
                    .path("havePermission").asBoolean(false);
        } catch (RuntimeException e) {
            // Unable to tell. Treated as administrator rather than as not: the safe answer to
            // "can this credential do everything" is the one that refuses.
            administrator = true;
        }

        return new Result(accountId, me.path("displayName").asText(null),
                me.path("emailAddress").asText(email), me.path("locale").asText(null),
                administrator);
    }

    private JsonNode get(String url, String authorization) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .header("Authorization", authorization)
                            .header("Accept", "application/json")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new CredentialRejected("INVALID");
            }
            if (response.statusCode() / 100 != 2) {
                throw new CredentialRejected("UNREACHABLE");
            }
            return JSON.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CredentialRejected("UNREACHABLE");
        } catch (CredentialRejected e) {
            throw e;
        } catch (Exception e) {
            throw new CredentialRejected("UNREACHABLE");
        }
    }

    /** Who the credential turned out to be. Never includes the credential. */
    public record Result(String accountId, String displayName, String email, String locale,
                         boolean administrator) {
    }

    /** @param code a stable reason, never Jira's message: its messages can quote the request */
    public static class CredentialRejected extends RuntimeException {

        private final String code;

        public CredentialRejected(String code) {
            super("the credential was rejected: " + code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
