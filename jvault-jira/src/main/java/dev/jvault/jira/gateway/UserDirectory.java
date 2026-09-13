package dev.jvault.jira.gateway;

import java.util.Collection;
import java.util.Map;

/**
 * Names for the account ids Jira stores people as.
 *
 * <p>Separate from {@link JiraMetadataGateway} because turning an id back into a name is all
 * that reading a ticket needs, and a reader has no business being handed the whole of Jira's
 * create metadata to get it.
 */
public interface UserDirectory {

    /**
     * Display names for account ids, in one call.
     *
     * <p>Jira stores who somebody is as an opaque id and jvault records the same, because that
     * is what a ticket was actually created with. Showing it back is another matter: nobody
     * recognises their colleague as 712020:8e1dc606, so anywhere a person reads a ticket the id
     * has to become a name again.
     *
     * <p>Ids that cannot be resolved are simply absent — a deactivated account, or one this
     * token cannot see. The caller falls back to the id rather than showing a blank.
     */
    Map<String, String> displayNamesOf(Collection<String> accountIds);
}
