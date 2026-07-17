package com.miniclaude.domain.scenario;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 不依赖具体数据库方言的 fail-closed SQL tokenizer/guard。 */
public final class SqlGuard {
    private static final Set<String> FORBIDDEN = new HashSet<>(Arrays.asList(
            "insert", "update", "delete", "drop", "alter", "create", "merge", "call",
            "execute", "exec", "truncate", "grant", "revoke", "copy", "unload", "into"));
    private static final Set<String> DANGEROUS_FUNCTIONS = new HashSet<>(Arrays.asList(
            "pg_sleep", "sleep", "benchmark", "load_file", "xp_cmdshell", "dblink",
            "read_csv", "read_parquet", "external_query"));

    public GuardedSql validate(String sql, int maximumRows) {
        if (sql == null || sql.trim().isEmpty()) throw new IllegalArgumentException("sql required");
        if (maximumRows < 1) throw new IllegalArgumentException("maximumRows must be positive");
        List<String> tokens = tokenize(sql);
        if (tokens.isEmpty() || (!"select".equals(tokens.get(0)) && !"with".equals(tokens.get(0)))) {
            throw new SecurityException("only SELECT statements are allowed");
        }
        for (String token : tokens) {
            if (";".equals(token)) throw new SecurityException("multiple statements are forbidden");
            if (FORBIDDEN.contains(token)) throw new SecurityException("forbidden SQL token: " + token);
            if (DANGEROUS_FUNCTIONS.contains(token)) {
                throw new SecurityException("dangerous SQL function: " + token);
            }
        }
        if (!tokens.contains("select")) throw new SecurityException("query must contain SELECT");
        int limitIndex = tokens.lastIndexOf("limit");
        if (limitIndex < 0 || limitIndex + 1 >= tokens.size()) {
            throw new SecurityException("explicit LIMIT is required");
        }
        int requested;
        try {
            requested = Integer.parseInt(tokens.get(limitIndex + 1));
        } catch (NumberFormatException invalid) {
            throw new SecurityException("LIMIT must be a literal integer");
        }
        if (requested < 1 || requested > maximumRows) {
            throw new SecurityException("LIMIT exceeds maximum rows: " + maximumRows);
        }
        return new GuardedSql(sql.trim(), requested);
    }

    private List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean single = false;
        boolean quotedIdentifier = false;
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
                if (single && next == '\'') { i++; continue; }
                single = !single; continue;
            }
            if (!single && c == '"') {
                flush(tokens, token); quotedIdentifier = !quotedIdentifier; continue;
            }
            if (single || quotedIdentifier) continue;
            if (Character.isLetterOrDigit(c) || c == '_') token.append(Character.toLowerCase(c));
            else {
                flush(tokens, token);
                if (c == ';' && !onlyWhitespaceAfter(sql, i + 1)) tokens.add(";");
            }
        }
        if (single || quotedIdentifier || blockComment) throw new SecurityException("unterminated SQL literal/comment");
        flush(tokens, token);
        return tokens;
    }

    private static boolean onlyWhitespaceAfter(String value, int from) {
        for (int i = from; i < value.length(); i++) if (!Character.isWhitespace(value.charAt(i))) return false;
        return true;
    }

    private static void flush(List<String> tokens, StringBuilder token) {
        if (token.length() > 0) {
            tokens.add(token.toString().toLowerCase(Locale.ROOT));
            token.setLength(0);
        }
    }

    public static final class GuardedSql {
        private final String sql;
        private final int limit;
        GuardedSql(String sql, int limit) { this.sql = sql; this.limit = limit; }
        public String getSql() { return sql; }
        public int getLimit() { return limit; }
    }
}
