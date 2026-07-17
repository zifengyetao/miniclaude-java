package com.miniclaude.domain.scenario;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 只读 guard 的词法与 fail-closed 契约测试。
 *
 * <p>既覆盖写操作、多语句、危险函数和无界结果的阻断，也证明字符串/注释中的关键字
 * 不会误报；真实数据库接入仍需使用只读账号和适配器权限作为第二道边界。</p>
 */
class SqlGuardTest {
    private final SqlGuard guard = new SqlGuard();

    @Test
    void acceptsSingleBoundedSelectIncludingCte() {
        assertThat(guard.validate("WITH x AS (SELECT 1) SELECT * FROM x LIMIT 25", 100).getLimit())
                .isEqualTo(25);
    }

    @Test
    void rejectsMutationMultipleStatementsDangerousFunctionsAndUnboundedResults() {
        // 每个样例代表独立逃逸面，任一命中都必须在调用数据适配器前阻断。
        assertBlocked("DELETE FROM users LIMIT 1", "only SELECT");
        assertBlocked("SELECT 1; SELECT 2 LIMIT 1", "multiple statements");
        assertBlocked("SELECT pg_sleep(10) LIMIT 1", "dangerous SQL function");
        assertBlocked("SELECT * FROM users", "explicit LIMIT");
        assertBlocked("SELECT * FROM users LIMIT 101", "exceeds maximum");
        assertBlocked("SELECT * INTO backup FROM users LIMIT 1", "forbidden SQL token");
    }

    @Test
    void doesNotTreatKeywordsInsideLiteralsOrCommentsAsStatements() {
        // why：guard 必须按词法上下文判断，否则合法文本数据会被当作可执行 SQL token。
        SqlGuard.GuardedSql result = guard.validate(
                "SELECT 'delete; pg_sleep' AS note /* DROP */ FROM facts LIMIT 1;", 10);
        assertThat(result.getLimit()).isEqualTo(1);
    }

    private void assertBlocked(String sql, String message) {
        assertThatThrownBy(() -> guard.validate(sql, 100))
                .isInstanceOf(SecurityException.class).hasMessageContaining(message);
    }
}
