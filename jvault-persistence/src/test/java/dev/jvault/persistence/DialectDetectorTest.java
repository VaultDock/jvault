package dev.jvault.persistence;

import dev.jvault.persistence.dialect.OracleDialect;
import dev.jvault.persistence.dialect.PostgresDialect;
import dev.jvault.persistence.dialect.SqlServerDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialectDetectorTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "PostgreSQL,                postgresql",
            "postgresql,                postgresql",
            "Microsoft SQL Server,      sqlserver",
            "microsoft sql server,      sqlserver",
            "Oracle,                    oracle",
    })
    @DisplayName("the dialect comes from the connected database, not from configuration")
    void detectsByProductName(String product, String expectedId) {
        assertThat(DialectDetector.forProduct(product.toLowerCase()).id()).isEqualTo(expectedId);
    }

    @Test
    @DisplayName("an unsupported database fails clearly rather than guessing")
    void unsupportedProductIsRejected() {
        assertThatThrownBy(() -> DialectDetector.forProduct("mysql"))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("unsupported database")
                .hasMessageContaining("PostgreSQL, Microsoft SQL Server and Oracle");
    }

    @Test
    @DisplayName("selecting an unverified dialect produces a warning a deployer can act on")
    void unverifiedDialectsAnnounceThemselves() {
        assertThat(DialectDetector.verificationNotice(new PostgresDialect()))
                .contains("is covered by integration tests");

        for (SqlDialect unverified : new SqlDialect[]{new SqlServerDialect(), new OracleDialect()}) {
            assertThat(DialectDetector.verificationNotice(unverified))
                    .as("dialect %s", unverified.id())
                    .contains("NOT been exercised against a real database");
        }
    }

    @Test
    @DisplayName("each dialect states honestly whether it has been proven")
    void verificationFlagsAreHonest() {
        // If a SQL Server or Oracle integration suite is ever added, these flags flip — and this
        // test is the reminder that the flag is a claim about evidence, not an aspiration.
        assertThat(new PostgresDialect().verifiedByIntegrationTests()).isTrue();
        assertThat(new SqlServerDialect().verifiedByIntegrationTests()).isFalse();
        assertThat(new OracleDialect().verifiedByIntegrationTests()).isFalse();
    }

    @Test
    @DisplayName("migrations are located per engine")
    void migrationLocationsAreDistinct() {
        assertThat(new PostgresDialect().migrationLocation()).endsWith("/postgresql");
        assertThat(new SqlServerDialect().migrationLocation()).endsWith("/sqlserver");
        assertThat(new OracleDialect().migrationLocation()).endsWith("/oracle");
    }
}
