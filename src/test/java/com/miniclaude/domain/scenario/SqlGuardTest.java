package com.miniclaude.domain.scenario;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {
    private final SqlGuard guard = new SqlGuard();

    @Test
    void acceptsSingleBoundedSelectIncludingCte() {
        assertThat(guard.validate("WITH x AS (SELECT 1) SELECT * FROM x LIMIT 25", 100).getLimit())
                .isEqualTo(25);
    }

    @Test
    void rejectsMutationMultipleStatementsDangerousFunctionsAndUnboundedResults() {
        assertBlocked("DELETE FROM users LIMIT 1", "only SELECT");
        assertBlocked("SELECT 1; SELECT 2 LIMIT 1", "multiple statements");
        assertBlocked("SELECT pg_sleep(10) LIMIT 1", "dangerous SQL function");
        assertBlocked("SELECT * FROM users", "explicit LIMIT");
        assertBlocked("SELECT * FROM users LIMIT 101", "exceeds maximum");
        assertBlocked("SELECT * INTO backup FROM users LIMIT 1", "forbidden SQL token");
    }

    @Test
    void doesNotTreatKeywordsInsideLiteralsOrCommentsAsStatements() {
        SqlGuard.GuardedSql result = guard.validate(
                "SELECT 'delete; pg_sleep' AS note /* DROP */ FROM facts LIMIT 1;", 10);
        assertThat(result.getLimit()).isEqualTo(1);
    }

    private void assertBlocked(String sql, String message) {
        assertThatThrownBy(() -> guard.validate(sql, 100))
                .isInstanceOf(SecurityException.class).hasMessageContaining(message);
    }
}
