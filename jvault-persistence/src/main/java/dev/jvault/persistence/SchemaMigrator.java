package dev.jvault.persistence;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Applies the migrations for the detected engine.
 *
 * <p>One logical schema, three physical script sets (decision D5). Selecting the location from
 * the detected dialect rather than from configuration means a deployment cannot run PostgreSQL's
 * DDL against Oracle because someone set the wrong property.
 */
public final class SchemaMigrator {

    private final DataSource dataSource;
    private final SqlDialect dialect;

    public SchemaMigrator(DataSource dataSource, SqlDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    public static SchemaMigrator detecting(DataSource dataSource) {
        return new SchemaMigrator(dataSource, DialectDetector.detect(dataSource));
    }

    public void migrate() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(dialect.migrationLocation())
                .loggers("slf4j")
                .load()
                .migrate();
    }

    public SqlDialect dialect() {
        return dialect;
    }
}
