/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.messaging.pravaha.catalog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Recognises and reads the catalogue's statements (ADR-059 §3), before Calcite sees anything -- the
 * way {@code ContinuousStatements} reads {@code CREATE CONTINUOUS QUERY}, and called by it.
 *
 * <pre>
 * CREATE NAMESPACE [IF NOT EXISTS] name [COMMENT 'text']
 * GRANT  privileges ON target TO   ROLE|USER name
 * REVOKE privileges ON target FROM ROLE|USER name
 * COMMENT ON target IS 'text' | NULL
 * ALTER target SET TAGS ('key' [= 'value'], ...)
 * ALTER target UNSET TAGS ('key', ...)
 * ALTER target OWNER TO ROLE|USER name
 * ALTER VIEW name SET NAMESPACE namespace
 * SHOW GRANTS ON target
 * SHOW GRANTS TO ROLE|USER name
 * SHOW EFFECTIVE ACCESS FOR USER name ON target
 * SHOW NAMESPACES
 *
 * CREATE ROW FILTER name AS predicate [EXCEPT ROLE r [, r]...]
 * CREATE MASK name ON COLUMN column AS expression [EXCEPT ROLE r [, r]...]
 * ALTER STREAM|VIEW|QUERY name SET POLICY policy      ALTER ... UNSET POLICY policy
 * ALTER TAG 'key[=value]' SET POLICY policy           ALTER TAG ... UNSET POLICY policy
 * DROP ROW FILTER [IF EXISTS] name                    DROP MASK [IF EXISTS] name
 * SHOW POLICIES [ON target]
 *
 * privileges := ALL [PRIVILEGES] | privilege [, privilege]...   (BUILD_ON may be written BUILD ON)
 * target     := CATALOG | TENANT t | NAMESPACE [t.]ns | VIEW|QUERY [[t.]ns.]name
 *             | STREAM name | SOURCE name | SINK name | LOOKUP name | POLICY [[t.]ns.]name
 * </pre>
 *
 * <p>Recognition is by the leading words only, as for the continuous-query statements: {@code GRANT},
 * {@code REVOKE}, {@code CREATE NAMESPACE}, {@code COMMENT ON}, {@code ALTER} followed by a kind of
 * object, and {@code SHOW GRANTS}, {@code SHOW EFFECTIVE} or {@code SHOW NAMESPACES}. A statement that
 * starts as one of these and goes wrong is refused with {@link Malformed}, which the SQL module turns
 * into {@code PRV-2070}; it never reaches a planner that would call it a syntax error.
 */
public final class CatalogStatements {

    /** A statement that begins as one of these and does not have its shape. */
    public static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String shape;

        Malformed(String message, String shape) {
            super(message);
            this.shape = shape;
        }

        /** The shape that was expected. */
        public String shape() {
            return shape;
        }
    }

    static final Set<String> KINDS = Set.of(
            "CATALOG",
            "TENANT",
            "NAMESPACE",
            "VIEW",
            "QUERY",
            "STREAM",
            "SOURCE",
            "SINK",
            "LOOKUP",
            "NOTIFIER",
            "ALERT",
            "POLICY");

    private CatalogStatements() {}

    /** Whether {@code sql} begins as a catalogue statement, well-formed or not. Never throws. */
    public static boolean recognizes(String sql) {
        if (sql == null) {
            return false;
        }
        List<Token> head;
        try {
            head = new Lexer(sql).head(3);
        } catch (Malformed e) {
            return false;
        }
        String first = word(head, 0);
        String second = word(head, 1);
        return switch (first) {
            case "GRANT", "REVOKE" -> true;
            case "CREATE" -> second.equals("NAMESPACE") || second.equals("MASK") || isRowFilter(head);
            case "DROP" -> second.equals("MASK") || isRowFilter(head);
            case "COMMENT" -> second.equals("ON");
            case "ALTER" -> second.equals("TAG") || KINDS.contains(second) && !second.equals("CATALOG");
            case "SHOW" ->
                second.equals("GRANTS")
                        || second.equals("EFFECTIVE")
                        || second.equals("NAMESPACES")
                        || second.equals("POLICIES");
            default -> false;
        };
    }

    /**
     * The statement {@code sql} is.
     *
     * @throws Malformed when it begins as a catalogue statement and does not have its shape
     * @throws IllegalArgumentException when it is not a catalogue statement at all
     */
    public static CatalogStatement parse(String sql) {
        if (!recognizes(sql)) {
            throw new IllegalArgumentException("not a catalogue statement");
        }
        return new Reader(sql).statement();
    }

    /** {@code ROW FILTER} as the second and third words: {@code CREATE ROW}, alone, is not ours. */
    private static boolean isRowFilter(List<Token> head) {
        return word(head, 1).equals("ROW") && word(head, 2).equals("FILTER");
    }

    private static String word(List<Token> tokens, int index) {
        return index < tokens.size() && tokens.get(index).kind == Kind.WORD
                ? tokens.get(index).text.toUpperCase(Locale.ROOT)
                : "";
    }

    // ------------------------------------------------------------------------------ the reader

    private static final class Reader {
        private final String sql;
        private final List<Token> tokens;
        private int at;
        private String shape = "a catalogue statement";

        Reader(String sql) {
            this.sql = sql;
            this.tokens = new Lexer(sql).all();
        }

        CatalogStatement statement() {
            String first = nextWord();
            CatalogStatement statement =
                    switch (first) {
                        case "GRANT" -> grant(true);
                        case "REVOKE" -> grant(false);
                        case "CREATE" -> isWord("NAMESPACE") ? createNamespace() : createPolicy();
                        case "DROP" -> dropPolicy();
                        case "COMMENT" -> comment();
                        case "ALTER" -> alter();
                        default -> show();
                    };
            if (peek().kind == Kind.SYMBOL && peek().text.equals(";")) {
                at++;
            }
            if (peek().kind != Kind.END) {
                throw malformed("'" + peek().text + "' follows a complete statement");
            }
            return statement;
        }

        private CatalogStatement grant(boolean granting) {
            shape = granting
                    ? "GRANT <privilege>[, ...] | ALL ON <target> TO ROLE|USER <name>"
                    : "REVOKE <privilege>[, ...] | ALL ON <target> FROM ROLE|USER <name>";
            Set<Privilege> privileges = EnumSet.noneOf(Privilege.class);
            if (isWord("ALL")) {
                at++;
                if (isWord("PRIVILEGES")) {
                    at++;
                }
            } else {
                do {
                    String word = nextWord();
                    if (word.equals("BUILD") && isWord("ON")) {
                        // BUILD ON as two words is BUILD_ON only when another ON follows it; otherwise the
                        // ON is the one that introduces the target, and BUILD alone is not a privilege.
                        if (!isWordAt(at + 1, "ON")) {
                            throw malformed("BUILD is not a privilege; write BUILD_ON (or BUILD ON ON <target>)");
                        }
                        at++;
                        word = "BUILD_ON";
                    }
                    try {
                        privileges.add(Privilege.parse(word));
                    } catch (RuntimeException e) {
                        throw malformed(e.getMessage());
                    }
                } while (symbol(","));
            }
            keyword("ON");
            CatalogStatement.Target target = target();
            keyword(granting ? "TO" : "FROM");
            Grantee grantee = grantee();
            return granting
                    ? new CatalogStatement.GrantPrivileges(privileges, target, grantee)
                    : new CatalogStatement.RevokePrivileges(privileges, target, grantee);
        }

        private CatalogStatement createNamespace() {
            shape = "CREATE NAMESPACE [IF NOT EXISTS] <name> [COMMENT '<text>']";
            keyword("NAMESPACE");
            boolean ifNotExists = false;
            if (isWord("IF")) {
                at++;
                keyword("NOT");
                keyword("EXISTS");
                ifNotExists = true;
            }
            List<String> parts = name(2);
            Optional<String> comment = Optional.empty();
            if (isWord("COMMENT")) {
                at++;
                comment = Optional.of(string());
            }
            return new CatalogStatement.CreateNamespace(parts, ifNotExists, comment);
        }

        private CatalogStatement createPolicy() {
            PolicyDefinition.Type type = policyType();
            shape = type == PolicyDefinition.Type.MASK
                    ? "CREATE MASK <name> ON COLUMN <column> AS <expression> [EXCEPT ROLE <role>[, ...]]"
                    : "CREATE ROW FILTER <name> AS <predicate> [EXCEPT ROLE <role>[, ...]]";
            List<String> parts = name(3);
            String column = "";
            if (type == PolicyDefinition.Type.MASK) {
                keyword("ON");
                keyword("COLUMN");
                column = part();
            }
            keyword("AS");
            String expression = expression();
            List<String> roles = new ArrayList<>();
            if (isWord("EXCEPT")) {
                at++;
                keyword("ROLE");
                do {
                    roles.add(part());
                } while (symbol(","));
            }
            return new CatalogStatement.CreatePolicy(type, parts, column, expression, roles);
        }

        private CatalogStatement dropPolicy() {
            PolicyDefinition.Type type = policyType();
            shape = "DROP " + type.words() + " [IF EXISTS] <name>";
            boolean ifExists = false;
            if (isWord("IF")) {
                at++;
                keyword("EXISTS");
                ifExists = true;
            }
            return new CatalogStatement.DropPolicy(type, name(3), ifExists);
        }

        private PolicyDefinition.Type policyType() {
            String word = nextWord();
            if (word.equals("MASK")) {
                return PolicyDefinition.Type.MASK;
            }
            keyword("FILTER");
            return PolicyDefinition.Type.ROW_FILTER;
        }

        /**
         * The policy's expression: every token up to {@code EXCEPT} outside parentheses, a {@code ;} or the
         * end, as the text it was written in. Checked by {@link PolicyExpression}, not here.
         */
        private String expression() {
            int first = at;
            int depth = 0;
            while (peek().kind != Kind.END) {
                Token token = peek();
                if (token.kind == Kind.SYMBOL && token.text.equals("(")) {
                    depth++;
                } else if (token.kind == Kind.SYMBOL && token.text.equals(")")) {
                    depth--;
                } else if (depth == 0 && token.kind == Kind.SYMBOL && token.text.equals(";")) {
                    break;
                } else if (depth == 0 && token.kind == Kind.WORD && token.text.equalsIgnoreCase("EXCEPT")) {
                    break;
                }
                at++;
            }
            if (at == first) {
                throw malformed("AS is followed by the policy's expression");
            }
            return sql.substring(tokens.get(first).from, tokens.get(at - 1).to);
        }

        private CatalogStatement comment() {
            shape = "COMMENT ON <target> IS '<text>' | NULL";
            keyword("ON");
            CatalogStatement.Target target = target();
            keyword("IS");
            if (isWord("NULL")) {
                at++;
                return new CatalogStatement.Comment(target, Optional.empty());
            }
            return new CatalogStatement.Comment(target, Optional.of(string()));
        }

        private CatalogStatement alter() {
            shape = "ALTER <target> SET TAGS (...) | UNSET TAGS (...) | OWNER TO ROLE|USER <name> | SET NAMESPACE <ns> "
                    + "| SET POLICY <policy> | UNSET POLICY <policy>";
            if (isWord("TAG")) {
                at++;
                shape = "ALTER TAG '<key>[=<value>]' SET POLICY <policy> | UNSET POLICY <policy>";
                CatalogStatement.Target tag = new CatalogStatement.Target("TAG", List.of(tagText()));
                String verb = nextWord();
                if (!verb.equals("SET") && !verb.equals("UNSET")) {
                    throw malformed("ALTER TAG takes SET POLICY or UNSET POLICY, not " + verb);
                }
                keyword("POLICY");
                return verb.equals("SET")
                        ? new CatalogStatement.SetPolicy(tag, name(3))
                        : new CatalogStatement.UnsetPolicy(tag, name(3));
            }
            CatalogStatement.Target target = target();
            String verb = nextWord();
            if ((verb.equals("SET") || verb.equals("UNSET")) && isWord("POLICY")) {
                at++;
                return verb.equals("SET")
                        ? new CatalogStatement.SetPolicy(target, name(3))
                        : new CatalogStatement.UnsetPolicy(target, name(3));
            }
            switch (verb) {
                case "OWNER" -> {
                    keyword("TO");
                    return new CatalogStatement.SetOwner(target, grantee());
                }
                case "UNSET" -> {
                    keyword("TAGS");
                    symbolRequired("(");
                    List<String> keys = new ArrayList<>();
                    do {
                        keys.add(tagText());
                    } while (symbol(","));
                    symbolRequired(")");
                    return new CatalogStatement.UnsetTags(target, keys);
                }
                case "SET" -> {
                    if (isWord("NAMESPACE")) {
                        at++;
                        return new CatalogStatement.SetNamespace(target, name(2));
                    }
                    keyword("TAGS");
                    symbolRequired("(");
                    Map<String, String> tags = new LinkedHashMap<>();
                    do {
                        String key = tagText();
                        String value = "";
                        if (symbol("=")) {
                            value = tagText();
                        }
                        tags.put(key, value);
                    } while (symbol(","));
                    symbolRequired(")");
                    return new CatalogStatement.SetTags(target, tags);
                }
                default -> throw malformed("ALTER " + target.kind() + " takes SET, UNSET or OWNER TO, not " + verb);
            }
        }

        private CatalogStatement show() {
            shape = "SHOW GRANTS ON <target> | SHOW GRANTS TO ROLE|USER <name> | "
                    + "SHOW EFFECTIVE ACCESS FOR USER <name> ON <target> | SHOW NAMESPACES | SHOW POLICIES [ON <target>]";
            String what = nextWord();
            switch (what) {
                case "POLICIES" -> {
                    if (isWord("ON")) {
                        at++;
                        return new CatalogStatement.ShowPolicies(Optional.of(target()));
                    }
                    return new CatalogStatement.ShowPolicies(Optional.empty());
                }
                case "NAMESPACES" -> {
                    return new CatalogStatement.ShowNamespaces();
                }
                case "GRANTS" -> {
                    String preposition = nextWord();
                    if (preposition.equals("ON")) {
                        return new CatalogStatement.ShowGrantsOn(target());
                    }
                    if (preposition.equals("TO")) {
                        return new CatalogStatement.ShowGrantsTo(grantee());
                    }
                    throw malformed("SHOW GRANTS is followed by ON <target> or TO ROLE|USER <name>");
                }
                default -> {
                    keyword("ACCESS");
                    keyword("FOR");
                    keyword("USER");
                    String user = part();
                    keyword("ON");
                    return new CatalogStatement.ShowEffectiveAccess(user, target());
                }
            }
        }

        private CatalogStatement.Target target() {
            String kind = nextWord();
            if (!KINDS.contains(kind)) {
                throw malformed("expected what the statement is about -- CATALOG, TENANT, NAMESPACE, VIEW, QUERY, "
                        + "STREAM, SOURCE, SINK, LOOKUP, NOTIFIER, ALERT or POLICY -- and found " + kind);
            }
            if (kind.equals("QUERY")) {
                kind = "VIEW";
            }
            return switch (kind) {
                case "CATALOG" -> new CatalogStatement.Target(kind, List.of());
                case "TENANT" -> new CatalogStatement.Target(kind, name(1));
                case "NAMESPACE" -> new CatalogStatement.Target(kind, name(2));
                default -> new CatalogStatement.Target(kind, name(3));
            };
        }

        private Grantee grantee() {
            String kind = nextWord();
            if (!kind.equals("ROLE") && !kind.equals("USER")) {
                throw malformed("a grantee is ROLE <name> or USER <name>");
            }
            return Grantee.parse(kind, part());
        }

        private List<String> name(int maxParts) {
            List<String> parts = new ArrayList<>();
            parts.add(part());
            while (peek().kind == Kind.SYMBOL && peek().text.equals(".")) {
                at++;
                parts.add(part());
            }
            if (parts.size() > maxParts) {
                throw malformed("'" + String.join(".", parts) + "' has " + parts.size() + " parts, and this names "
                        + "at most " + maxParts);
            }
            return parts;
        }

        private String part() {
            Token token = next();
            if (token.kind != Kind.WORD && token.kind != Kind.QUOTED) {
                throw malformed("expected a name and found '" + token.text + "'");
            }
            return token.text;
        }

        private String string() {
            Token token = next();
            if (token.kind != Kind.STRING) {
                throw malformed("expected a quoted string and found '" + token.text + "'");
            }
            return token.text;
        }

        private String tagText() {
            Token token = next();
            if (token.kind != Kind.STRING && token.kind != Kind.WORD && token.kind != Kind.QUOTED) {
                throw malformed("expected a tag, as 'key' or 'key' = 'value', and found '" + token.text + "'");
            }
            return token.text;
        }

        private boolean isWordAt(int index, String word) {
            return index < tokens.size()
                    && tokens.get(index).kind == Kind.WORD
                    && tokens.get(index).text.equalsIgnoreCase(word);
        }

        private boolean isWord(String word) {
            return peek().kind == Kind.WORD && peek().text.equalsIgnoreCase(word);
        }

        private void keyword(String word) {
            Token token = next();
            if (token.kind != Kind.WORD || !token.text.equalsIgnoreCase(word)) {
                throw malformed("expected " + word + " and found '" + token.text + "'");
            }
        }

        private boolean symbol(String symbol) {
            if (peek().kind == Kind.SYMBOL && peek().text.equals(symbol)) {
                at++;
                return true;
            }
            return false;
        }

        private void symbolRequired(String symbol) {
            if (!symbol(symbol)) {
                throw malformed("expected '" + symbol + "' and found '" + peek().text + "'");
            }
        }

        private String nextWord() {
            Token token = next();
            if (token.kind != Kind.WORD) {
                throw malformed("expected a keyword and found '" + token.text + "'");
            }
            return token.text.toUpperCase(Locale.ROOT);
        }

        private Token peek() {
            return tokens.get(Math.min(at, tokens.size() - 1));
        }

        private Token next() {
            Token token = peek();
            if (token.kind == Kind.END) {
                throw malformed("the statement ends early");
            }
            at++;
            return token;
        }

        private Malformed malformed(String what) {
            return new Malformed(what + ". Expected: " + shape, shape);
        }
    }

    // ----------------------------------------------------------------------------- the lexer

    enum Kind {
        WORD,
        QUOTED,
        STRING,
        SYMBOL,
        END
    }

    /** A token, and where it stands in the statement: {@code from} inclusive, {@code to} exclusive. */
    record Token(Kind kind, String text, int from, int to) {}

    /** Words, "quoted identifiers", 'strings', and single-character symbols; {@code --} comments skipped. */
    private static final class Lexer {
        private final String sql;
        private int at;

        Lexer(String sql) {
            this.sql = sql;
        }

        List<Token> head(int count) {
            List<Token> head = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Token token = next();
                head.add(token);
                if (token.kind == Kind.END) {
                    break;
                }
            }
            return head;
        }

        List<Token> all() {
            List<Token> all = new ArrayList<>();
            Token token;
            do {
                token = next();
                all.add(token);
            } while (token.kind != Kind.END);
            return all;
        }

        private Token next() {
            skipSpace();
            if (at >= sql.length()) {
                return new Token(Kind.END, "<end>", at, at);
            }
            char c = sql.charAt(at);
            if (Character.isLetter(c) || c == '_') {
                int start = at;
                while (at < sql.length() && (Character.isLetterOrDigit(sql.charAt(at)) || sql.charAt(at) == '_')) {
                    at++;
                }
                return new Token(Kind.WORD, sql.substring(start, at), start, at);
            }
            if (Character.isDigit(c)) {
                int start = at;
                while (at < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(at))
                                || sql.charAt(at) == '_'
                                || sql.charAt(at) == '.')) {
                    at++;
                }
                return new Token(Kind.WORD, sql.substring(start, at), start, at);
            }
            if (c == '"' || c == '\'') {
                return quoted(c);
            }
            at++;
            return new Token(Kind.SYMBOL, String.valueOf(c), at - 1, at);
        }

        private Token quoted(char quote) {
            StringBuilder text = new StringBuilder();
            int start = at;
            at++;
            while (true) {
                if (at >= sql.length()) {
                    throw new Malformed("a quoted " + (quote == '"' ? "name" : "string") + " is not closed", "");
                }
                char c = sql.charAt(at++);
                if (c == quote) {
                    if (at < sql.length() && sql.charAt(at) == quote) {
                        text.append(quote);
                        at++;
                        continue;
                    }
                    break;
                }
                text.append(c);
            }
            return new Token(quote == '"' ? Kind.QUOTED : Kind.STRING, text.toString(), start, at);
        }

        private void skipSpace() {
            while (at < sql.length()) {
                char c = sql.charAt(at);
                if (Character.isWhitespace(c)) {
                    at++;
                } else if (c == '-' && at + 1 < sql.length() && sql.charAt(at + 1) == '-') {
                    while (at < sql.length() && sql.charAt(at) != '\n') {
                        at++;
                    }
                } else {
                    break;
                }
            }
        }
    }
}
