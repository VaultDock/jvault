package dev.jvault.persistence;

import dev.jvault.content.CommentRecord;
import dev.jvault.content.CommentRepository;
import dev.jvault.domain.placement.Placement;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Comments on Spring JDBC.
 *
 * <p>{@code jira_body} holds what Jira shows — either the comment itself, when policy placed it
 * there, or the surrogate standing in for it. The comment that was replaced by a surrogate is not
 * in this table at all; it is in the vault, behind {@code content_ref}.
 */
public final class JdbcCommentRepository implements CommentRepository {

    private static final String COLUMNS = """
            comment_ref, ticket_ref, jira_body, content_ref, placement, author_id,
            jira_comment_id, created_at""";

    private final JdbcTemplate jdbc;

    public JdbcCommentRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void save(CommentRecord comment) {
        int updated = jdbc.update("""
                        UPDATE ticket_comment
                        SET jira_body = ?, content_ref = ?, placement = ?, jira_comment_id = ?
                        WHERE comment_ref = ?""",
                comment.jiraBody(), comment.contentRef(), comment.placement().name(),
                comment.jiraCommentId(), comment.commentRef());

        if (updated == 0) {
            jdbc.update("INSERT INTO ticket_comment (" + COLUMNS + ")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    comment.commentRef(), comment.ticketRef(), comment.jiraBody(),
                    comment.contentRef(), comment.placement().name(), comment.authorId(),
                    comment.jiraCommentId(), Timestamp.from(comment.createdAt()));
        }
    }

    @Override
    public Optional<CommentRecord> find(String commentRef) {
        List<CommentRecord> found = jdbc.query(
                "SELECT " + COLUMNS + " FROM ticket_comment WHERE comment_ref = ?",
                JdbcCommentRepository::map, commentRef);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<CommentRecord> forTicket(String ticketRef) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ticket_comment WHERE ticket_ref = ?"
                        + " ORDER BY created_at, comment_ref",
                JdbcCommentRepository::map, ticketRef);
    }

    private static CommentRecord map(ResultSet rs, int rowNum) throws SQLException {
        String contentRef = rs.getString("content_ref");
        return new CommentRecord(
                rs.getString("comment_ref").trim(),
                rs.getString("ticket_ref").trim(),
                rs.getString("jira_body"),
                contentRef == null ? null : contentRef.trim(),
                Placement.valueOf(rs.getString("placement")),
                rs.getString("author_id"),
                rs.getString("jira_comment_id"),
                rs.getTimestamp("created_at").toInstant());
    }
}
