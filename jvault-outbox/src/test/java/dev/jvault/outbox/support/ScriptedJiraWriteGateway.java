package dev.jvault.outbox.support;

import dev.jvault.jira.egress.JiraSafePayload;
import dev.jvault.jira.gateway.JiraWriteGateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A gateway that returns pre-scripted outcomes and records what it was asked to send. */
public final class ScriptedJiraWriteGateway implements JiraWriteGateway {

    private final Deque<JiraWriteResult> scripted = new ArrayDeque<>();
    private final List<JiraSafePayload> sent = new ArrayList<>();
    private JiraWriteResult fallback = JiraWriteResult.succeeded("10001", "SEC-1");

    public ScriptedJiraWriteGateway then(JiraWriteResult result) {
        scripted.addLast(result);
        return this;
    }

    public ScriptedJiraWriteGateway fallback(JiraWriteResult result) {
        this.fallback = result;
        return this;
    }

    @Override
    public JiraWriteResult execute(JiraSafePayload payload) {
        sent.add(payload);
        return scripted.isEmpty() ? fallback : scripted.removeFirst();
    }

    public List<JiraSafePayload> sent() {
        return List.copyOf(sent);
    }

    public int sendCount() {
        return sent.size();
    }
}
