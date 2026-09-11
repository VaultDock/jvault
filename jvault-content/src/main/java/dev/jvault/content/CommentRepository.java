package dev.jvault.content;

import java.util.List;
import java.util.Optional;

/** Persistence port for comments. */
public interface CommentRepository {

    void save(CommentRecord comment);

    Optional<CommentRecord> find(String commentRef);

    List<CommentRecord> forTicket(String ticketRef);
}
