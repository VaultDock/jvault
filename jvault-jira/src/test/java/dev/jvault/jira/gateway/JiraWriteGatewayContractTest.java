package dev.jvault.jira.gateway;

import dev.jvault.jira.egress.JiraSafePayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway's value is entirely in what it refuses to accept, so the shape of its interface
 * is itself a thing worth testing.
 */
class JiraWriteGatewayContractTest {

    @Test
    @DisplayName("every mutating method accepts only a JiraSafePayload")
    void gatewayAcceptsNothingButSafePayloads() {
        Method[] methods = JiraWriteGateway.class.getDeclaredMethods();

        assertThat(methods).isNotEmpty();

        for (Method method : methods) {
            assertThat(method.getParameterTypes())
                    .as("%s takes %s; an overload accepting a map, a string or a raw request "
                            + "would silently remove the guarantee that every Jira write has "
                            + "been through the egress guard",
                            method.getName(), Arrays.toString(method.getParameterTypes()))
                    .containsExactly(JiraSafePayload.class);
        }
    }

    @Test
    @DisplayName("an unknown Jira outcome is representable, so callers cannot ignore ambiguity")
    void ambiguousOutcomeExists() {
        assertThat(JiraWriteGateway.JiraWriteResult.Outcome.values())
                .contains(JiraWriteGateway.JiraWriteResult.Outcome.AMBIGUOUS);
    }
}
