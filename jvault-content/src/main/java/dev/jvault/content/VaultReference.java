package dev.jvault.content;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one pointer from a Jira issue back to jvault.
 *
 * <p>Exactly one, deliberately. A ticket can have a secured description, four secured custom
 * fields and a dozen attachments, and an earlier version put a link on the issue for each of
 * them — a wall of identical-looking links to places a reader cannot distinguish and mostly
 * cannot open. What somebody reading the issue actually wants is "show me what is missing from
 * this", which is one question with one answer: the ticket in jvault.
 *
 * <p>The effect key and the globalId are both derived from the ticket reference alone, so this
 * survives being enqueued repeatedly. The outbox deduplicates on {@code (ticketRef, effectKey)}
 * and Jira upserts remote links on globalId, which means every later attachment can ask for the
 * reference without ever producing a second one.
 */
public final class VaultReference {

    /** Stable per ticket: appending it again returns the entry already queued or sent. */
    public static final String EFFECT_KEY = "remote-link:ticket";

    private VaultReference() {
    }

    /**
     * The remote link payload for a ticket.
     *
     * <p>The title and summary say that something is held elsewhere and nothing about what. A
     * remote link is visible to everyone who can see the issue, which is a wider audience than
     * the content's own, so naming the secured fields here would leak by description what the
     * placement went to the trouble of removing. It carries no count either: attachments arrive
     * after the link is written, and Jira would be left asserting a number that quietly stopped
     * being true.
     */
    static Map<String, String> payload(String ticketRef, String ticketUrl) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("globalId", globalId(ticketRef));
        fields.put("url", ticketUrl);
        fields.put("title", "Secured content in jvault");
        fields.put("summary", "Parts of this issue are held in jvault, not in Jira.");
        return fields;
    }

    public static String globalId(String ticketRef) {
        return "jvault:ticket:" + ticketRef;
    }
}
