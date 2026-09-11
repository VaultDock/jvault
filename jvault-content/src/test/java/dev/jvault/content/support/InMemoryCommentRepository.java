package dev.jvault.content.support;

import dev.jvault.content.CommentRecord;
import dev.jvault.content.CommentRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryCommentRepository implements CommentRepository {

    private final Map<String, CommentRecord> byRef = new LinkedHashMap<>();

    @Override
    public void save(CommentRecord comment) {
        byRef.put(comment.commentRef(), comment);
    }

    @Override
    public Optional<CommentRecord> find(String commentRef) {
        return Optional.ofNullable(byRef.get(commentRef));
    }

    @Override
    public List<CommentRecord> forTicket(String ticketRef) {
        var found = new ArrayList<CommentRecord>();
        byRef.values().stream().filter(c -> c.ticketRef().equals(ticketRef)).forEach(found::add);
        return found;
    }
}
