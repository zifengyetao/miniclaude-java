package com.miniclaude.domain.scenario;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 不依赖具体数据库方言的 fail-closed SQL 只读 Guard（领域层纵深防御）。
 * <p>
 * <b>为何放在 domain：</b>数据分析场景的「只允许 SELECT + 字面量 LIMIT」是业务安全规则，
 * 应在 domain 声明并在 application 调用，而非散落在 JDBC 适配器。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>仅允许以 SELECT/WITH 开头的单语句；禁止多语句、DML/DDL、危险函数。</li>
 *   <li>LIMIT 必须为字面量整数且 ≤ maximumRows。</li>
 *   <li>未闭合字符串/注释 → SecurityException（fail-closed）。</li>
 * </ul>
 * <p>
 * <b>边界：</b>不替代 DB 只读账号与网络隔离；{@link ScenarioPorts.AnalyticsData#executeReadOnly} 前应调用。
 */
public final class SqlGuard {

    /** 禁止出现的 SQL 关键字（小写 token 匹配）。 */
    private static final Set<String> FORBIDDEN = new HashSet<>(Arrays.asList(
            "insert", "update", "delete", "drop", "alter", "create", "merge", "call",
            "execute", "exec", "truncate", "grant", "revoke", "copy", "unload", "into"));
    /** 禁止的危险函数 token。 */
    private static final Set<String> DANGEROUS_FUNCTIONS = new HashSet<>(Arrays.asList(
            "pg_sleep", "sleep", "benchmark", "load_file", "xp_cmdshell", "dblink",
            "read_csv", "read_parquet", "external_query"));

    /**
     * 校验 SQL 是否满足只读 guard 规则。
     *
     * @param sql          待执行 SQL
     * @param maximumRows  允许的最大 LIMIT 值（平台上限）
     * @return 规范化后的 sql 与解析出的 limit
     * @throws IllegalArgumentException sql 空或 maximumRows &lt; 1
     * @throws SecurityException        违反只读/单语句/LIMIT 规则
     */
    public GuardedSql validate(String sql, int maximumRows) {
        if (sql == null || sql.trim().isEmpty()) throw new IllegalArgumentException("sql required");
        if (maximumRows < 1) throw new IllegalArgumentException("maximumRows must be positive");
        List<String> tokens = tokenize(sql);
        // 入口必须是 select 或 with（CTE），否则拒绝未知语法
        if (tokens.isEmpty() || (!"select".equals(tokens.get(0)) && !"with".equals(tokens.get(0)))) {
            throw new SecurityException("only SELECT statements are allowed");
        }
        for (String token : tokens) {
            // 非末尾分号表示可能的多语句注入
            if (";".equals(token)) throw new SecurityException("multiple statements are forbidden");
            if (FORBIDDEN.contains(token)) throw new SecurityException("forbidden SQL token: " + token);
            if (DANGEROUS_FUNCTIONS.contains(token)) {
                throw new SecurityException("dangerous SQL function: " + token);
            }
        }
        if (!tokens.contains("select")) throw new SecurityException("query must contain SELECT");
        int limitIndex = tokens.lastIndexOf("limit");
        // 必须显式 LIMIT，防止全表扫描外泄
        if (limitIndex < 0 || limitIndex + 1 >= tokens.size()) {
            throw new SecurityException("explicit LIMIT is required");
        }
        int requested;
        try {
            requested = Integer.parseInt(tokens.get(limitIndex + 1));
        } catch (NumberFormatException invalid) {
            // 拒绝 LIMIT ? / 子查询形式，上界必须在静态可判定
            throw new SecurityException("LIMIT must be a literal integer");
        }
        if (requested < 1 || requested > maximumRows) {
            throw new SecurityException("LIMIT exceeds maximum rows: " + maximumRows);
        }
        return new GuardedSql(sql.trim(), requested);
    }

    /**
     * 词法分析：跳过字符串、双引号标识符、行/块注释内的 token。
     * <p>输出小写 keyword token 列表，用于关键字黑名单匹配。
     */
    private List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean single = false;           // 单引号字符串
        boolean quotedIdentifier = false; // 双引号标识符
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') { blockComment = false; i++; }
                continue;
            }
            if (!single && !quotedIdentifier && c == '-' && next == '-') {
                flush(tokens, token); lineComment = true; i++; continue;
            }
            if (!single && !quotedIdentifier && c == '/' && next == '*') {
                flush(tokens, token); blockComment = true; i++; continue;
            }
            if (!quotedIdentifier && c == '\'') {
                flush(tokens, token);
                if (single && next == '\'') { i++; continue; } // SQL 转义 ''
                single = !single; continue;
            }
            if (!single && c == '"') {
                flush(tokens, token); quotedIdentifier = !quotedIdentifier; continue;
            }
            if (single || quotedIdentifier) continue;
            if (Character.isLetterOrDigit(c) || c == '_') token.append(Character.toLowerCase(c));
            else {
                flush(tokens, token);
                // 仅当分号后还有非空白内容时才记录 ';' token（检测多语句）
                if (c == ';' && !onlyWhitespaceAfter(sql, i + 1)) tokens.add(";");
            }
        }
        if (single || quotedIdentifier || blockComment) {
            throw new SecurityException("unterminated SQL literal/comment");
        }
        flush(tokens, token);
        return tokens;
    }

    /** 检查从 from 起是否全是空白（用于判断末尾分号）。 */
    private static boolean onlyWhitespaceAfter(String value, int from) {
        for (int i = from; i < value.length(); i++) if (!Character.isWhitespace(value.charAt(i))) return false;
        return true;
    }

    /** 将当前 token 缓冲 flush 到列表。 */
    private static void flush(List<String> tokens, StringBuilder token) {
        if (token.length() > 0) {
            tokens.add(token.toString().toLowerCase(Locale.ROOT));
            token.setLength(0);
        }
    }

    /** 通过 guard 的 SQL 与解析出的 LIMIT 值。 */
    public static final class GuardedSql {
        private final String sql;
        private final int limit;
        GuardedSql(String sql, int limit) { this.sql = sql; this.limit = limit; }
        /** @return trim 后的 SQL */
        public String getSql() { return sql; }
        /** @return 字面量 LIMIT 整数 */
        public int getLimit() { return limit; }
    }
}
