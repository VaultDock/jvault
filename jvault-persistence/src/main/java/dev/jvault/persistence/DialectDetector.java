package dev.jvault.persistence;

import dev.jvault.persistence.dialect.OracleDialect;
import dev.jvault.persistence.dialect.PostgresDialect;
import dev.jvault.persistence.dialect.SqlServerDialect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Picks the dialect from the connected database, rather than from configuration.
 *
 * <p>Detecting beats configuring here: a deployment that points at PostgreSQL while configured
 * for Oracle would otherwise run Oracle's two-statement claim against a PostgreSQL server, and
 * the failure would appear as a puzzling SQL error rather than a clear mismatch.
 */
public final class DialectDetector {

    private DialectDetector() {
    }

    public static SqlDialect detect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName()
                    .toLowerCase(Locale.ROOT);
            return forProduct(product);
        } catch (SQLException e) {
            throw new PersistenceException("could not determine the database product", e);
        }
    }

    static SqlDialect forProduct(String product) {
        if (product.contains("postgres")) {
            return new PostgresDialect();
        }
        if (product.contains("microsoft sql server") || product.contains("sql server")) {
            return new SqlServerDialect();
        }
        if (product.contains("oracle")) {
            return new OracleDialect();
        }
        throw new PersistenceException(
                "unsupported database: '" + product + "'. jvault supports PostgreSQL, "
                        + "Microsoft SQL Server and Oracle (decision D5).", null);
    }

    /**
     * Warns when a dialect that has never been exercised against a real engine is selected.
     *
     * <p>An unverified dialect is not the same as a broken one, but it is not the same as a
     * proven one either, and a deployer deserves to know which they have before the first
     * production dequeue rather than after.
     */
    public static String verificationNotice(SqlDialect dialect) {
        if (dialect.verifiedByIntegrationTests()) {
            return "jvault persistence dialect '" + dialect.id() + "' is covered by integration tests.";
        }
        return "jvault persistence dialect '" + dialect.id() + "' has NOT been exercised against a"
                + " real database in this build. Its SQL is reviewed but unproven — verify the"
                + " outbox claim under concurrency before relying on it.";
    }
}
