package io.github.xthefox.crb.molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small Molang compiler for Bedrock particle effects (and anything else data-driven).
 *
 * <p>Supports what JJA's 3,468 particle effects use: numbers, arithmetic, comparisons, logical
 * operators, {@code ?:} and {@code ??}, {@code math.*} functions (trig in degrees, like Bedrock),
 * {@code variable./v.}, {@code temp./t.}, {@code query./q.}, {@code context./c.} names,
 * assignments and multi-statement bodies with {@code return}. Unknown names evaluate to 0, as in
 * Bedrock. Names are case-insensitive.
 *
 * <p>Expressions compile once into a tree of {@link Expr} nodes; variables resolve to array slots
 * ({@link MolangContext}), so evaluation does no string work.
 */
public final class Molang {
    private static final Map<String, Expr> CACHE = new ConcurrentHashMap<>();

    private Molang() {}

    /** Compiled Molang expression. */
    @FunctionalInterface
    public interface Expr {
        double eval(MolangContext ctx);

        default boolean isConstant() {
            return false;
        }
    }

    public record Constant(double value) implements Expr {
        @Override public double eval(MolangContext ctx) { return value; }
        @Override public boolean isConstant() { return true; }
    }

    public static final Expr ZERO = new Constant(0);
    public static final Expr ONE = new Constant(1);

    public static Expr constant(double v) {
        return v == 0 ? ZERO : v == 1 ? ONE : new Constant(v);
    }

    /** Compiles source; malformed input compiles to 0 (Bedrock ignores broken expressions too). */
    public static Expr compile(String source) {
        if (source == null || source.isBlank()) return ZERO;
        return CACHE.computeIfAbsent(source, s -> {
            try {
                return new Parser(s, true).program();
            } catch (RuntimeException e) {
                return ZERO;
            }
        });
    }

    /** Strict variant for tests and tools: throws on malformed input. */
    public static Expr compileStrict(String source) {
        return new Parser(source, false).program();
    }

    /** Lenient compile that reports failure instead of returning 0 (for tests). */
    public static Expr compileLenient(String source) {
        return new Parser(source, true).program();
    }

    // ------------------------------------------------------------------------------------- lexer

    enum T { NUM, NAME, STR, OP, LPAREN, RPAREN, LBRACE, RBRACE, COMMA, SEMI, EOF }

    record Tok(T type, String text, double num) {}

    static List<Tok> lex(String s, boolean lenient) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int j = i;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                double v = Double.parseDouble(s.substring(i, j));
                if (j < n && (s.charAt(j) == 'f' || s.charAt(j) == 'F')) j++;
                out.add(new Tok(T.NUM, s.substring(i, j), v));
                i = j;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.')) j++;
                out.add(new Tok(T.NAME, s.substring(i, j).toLowerCase(Locale.ROOT), 0));
                i = j;
            } else if (c == '\'') {
                int j = s.indexOf('\'', i + 1);
                if (j < 0) j = n;
                out.add(new Tok(T.STR, s.substring(i + 1, Math.min(j, n)), 0));
                i = Math.min(j + 1, n);
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                switch (two) {
                    case "==", "!=", "<=", ">=", "&&", "||", "??", "->" -> { out.add(new Tok(T.OP, two, 0)); i += 2; continue; }
                    default -> { }
                }
                switch (c) {
                    case '(' -> out.add(new Tok(T.LPAREN, "(", 0));
                    case ')' -> out.add(new Tok(T.RPAREN, ")", 0));
                    case '{' -> out.add(new Tok(T.LBRACE, "{", 0));
                    case '}' -> out.add(new Tok(T.RBRACE, "}", 0));
                    case ',' -> out.add(new Tok(T.COMMA, ",", 0));
                    case ';' -> out.add(new Tok(T.SEMI, ";", 0));
                    case '+', '-', '*', '/', '<', '>', '!', '?', ':', '=' -> out.add(new Tok(T.OP, String.valueOf(c), 0));
                    default -> {
                        if (!lenient) throw new IllegalArgumentException("Unexpected '" + c + "' in " + s);
                        // JJA typos such as a stray backtick: skip the character
                    }
                }
                i++;
            }
        }
        out.add(new Tok(T.EOF, "", 0));
        return out;
    }

    // ------------------------------------------------------------------------------------ parser

    static final class Parser {
        private final List<Tok> toks;
        private final boolean lenient;
        private int p;

        Parser(String src, boolean lenient) {
            this.lenient = lenient;
            this.toks = lex(src, lenient);
        }

        private Tok peek() { return toks.get(p); }
        private Tok next() { return toks.get(p++); }
        private boolean op(String o) {
            Tok t = peek();
            if (t.type == T.OP && t.text.equals(o)) { p++; return true; }
            return false;
        }
        private void expect(T type) {
            if (peek().type == type) { p++; return; }
            // lenient: auto-close a missing ')' / '}'
            if (!lenient) throw new IllegalArgumentException("Expected " + type);
        }

        Expr program() {
            List<Expr> stmts = new ArrayList<>();
            boolean multi = false;
            Expr returned = null;
            while (peek().type != T.EOF && peek().type != T.RBRACE) {
                if (peek().type == T.SEMI) { next(); multi = true; continue; }
                if (lenient && (peek().type == T.RPAREN || peek().type == T.COMMA)) break; // trailing junk
                if (lenient && !stmts.isEmpty() && !multi) break; // e.g. "6 2": keep the first value
                if (peek().type == T.NAME && peek().text.equals("return")) {
                    next();
                    Expr r = expr();
                    stmts.add(r);
                    returned = r;
                    multi = true;
                    continue;
                }
                stmts.add(statement());
            }
            if (!multi && stmts.size() == 1) return stmts.get(0);
            if (stmts.isEmpty()) return ZERO;
            Expr[] body = stmts.toArray(Expr[]::new);
            Expr ret = returned;
            return ctx -> {
                double v = 0;
                for (Expr e : body) {
                    v = e.eval(ctx);
                    if (e == ret) return v;
                }
                return ret == null ? 0 : v;
            };
        }

        Expr statement() {
            Tok t = peek();
            if (t.type == T.NAME && toks.get(p + 1).type == T.OP && toks.get(p + 1).text.equals("=")) {
                next();
                next();
                int slot = MolangContext.slot(normalize(t.text));
                Expr value = expr();
                return ctx -> {
                    double v = value.eval(ctx);
                    ctx.set(slot, v);
                    return v;
                };
            }
            return expr();
        }

        Expr expr() {
            Expr cond = coalesce();
            if (op("?")) {
                Expr a = expr();
                if (op(":")) {
                    Expr b = expr();
                    return ctx -> cond.eval(ctx) != 0 ? a.eval(ctx) : b.eval(ctx);
                }
                return ctx -> cond.eval(ctx) != 0 ? a.eval(ctx) : 0;
            }
            return cond;
        }

        Expr coalesce() {
            Expr l = or();
            while (op("??")) {
                Expr left = l, right = or();
                if (left instanceof Var v) {
                    int slot = v.slot;
                    l = ctx -> ctx.isSet(slot) ? ctx.get(slot) : right.eval(ctx);
                } else {
                    l = left;
                }
            }
            return l;
        }

        Expr or() {
            Expr l = and();
            while (op("||")) { Expr a = l, b = and(); l = ctx -> a.eval(ctx) != 0 || b.eval(ctx) != 0 ? 1 : 0; }
            return l;
        }

        Expr and() {
            Expr l = equality();
            while (op("&&")) { Expr a = l, b = equality(); l = ctx -> a.eval(ctx) != 0 && b.eval(ctx) != 0 ? 1 : 0; }
            return l;
        }

        Expr equality() {
            Expr l = comparison();
            while (true) {
                if (op("==")) { Expr a = l, b = comparison(); l = fold(a, b, ctx -> a.eval(ctx) == b.eval(ctx) ? 1 : 0); }
                else if (op("!=")) { Expr a = l, b = comparison(); l = fold(a, b, ctx -> a.eval(ctx) != b.eval(ctx) ? 1 : 0); }
                else return l;
            }
        }

        Expr comparison() {
            Expr l = additive();
            while (true) {
                if (op("<")) { Expr a = l, b = additive(); l = fold(a, b, ctx -> a.eval(ctx) < b.eval(ctx) ? 1 : 0); }
                else if (op("<=")) { Expr a = l, b = additive(); l = fold(a, b, ctx -> a.eval(ctx) <= b.eval(ctx) ? 1 : 0); }
                else if (op(">")) { Expr a = l, b = additive(); l = fold(a, b, ctx -> a.eval(ctx) > b.eval(ctx) ? 1 : 0); }
                else if (op(">=")) { Expr a = l, b = additive(); l = fold(a, b, ctx -> a.eval(ctx) >= b.eval(ctx) ? 1 : 0); }
                else return l;
            }
        }

        Expr additive() {
            Expr l = mult();
            while (true) {
                if (op("+")) { Expr a = l, b = mult(); l = fold(a, b, ctx -> a.eval(ctx) + b.eval(ctx)); }
                else if (op("-")) { Expr a = l, b = mult(); l = fold(a, b, ctx -> a.eval(ctx) - b.eval(ctx)); }
                else return l;
            }
        }

        Expr mult() {
            Expr l = unary();
            while (true) {
                if (op("*")) { Expr a = l, b = unary(); l = fold(a, b, ctx -> a.eval(ctx) * b.eval(ctx)); }
                else if (op("/")) {
                    Expr a = l, b = unary();
                    l = fold(a, b, ctx -> {
                        double d = b.eval(ctx);
                        return d == 0 ? 0 : a.eval(ctx) / d;
                    });
                } else return l;
            }
        }

        Expr unary() {
            if (op("-")) { Expr e = unary(); return fold(e, e, ctx -> -e.eval(ctx)); }
            if (op("+")) return unary();
            if (op("!")) { Expr e = unary(); return fold(e, e, ctx -> e.eval(ctx) == 0 ? 1 : 0); }
            return primary();
        }

        Expr primary() {
            Tok t = next();
            switch (t.type) {
                case NUM -> { return constant(t.num); }
                case STR -> { return ZERO; }
                case LPAREN -> {
                    Expr e = expr();
                    expect(T.RPAREN);
                    return e;
                }
                case LBRACE -> {
                    Expr e = program();
                    expect(T.RBRACE);
                    return e;
                }
                case NAME -> {
                    if (peek().type == T.LPAREN) {
                        next();
                        List<Expr> args = new ArrayList<>();
                        if (peek().type != T.RPAREN) {
                            do args.add(expr()); while (peek().type == T.COMMA && next() != null);
                        }
                        expect(T.RPAREN);
                        return MolangMath.call(t.text, args.toArray(Expr[]::new));
                    }
                    return name(t.text);
                }
                default -> throw new IllegalArgumentException("Unexpected token " + t.text);
            }
        }

        private Expr name(String raw) {
            String n = normalize(raw);
            if (n.equals("math.pi")) return constant(Math.PI);
            if (n.equals("true")) return ONE;
            if (n.equals("false")) return ZERO;
            if (n.startsWith("query.")) {
                Expr q = MolangContext.query(n);
                if (q != null) return q;
            }
            return new Var(MolangContext.slot(n));
        }

        /** Folds a binary/unary op over constants at compile time. */
        private static Expr fold(Expr a, Expr b, Expr op) {
            if (a.isConstant() && b.isConstant()) return constant(op.eval(null));
            return op;
        }
    }

    static String normalize(String n) {
        if (n.startsWith("v.")) return "variable." + n.substring(2);
        if (n.startsWith("t.")) return "temp." + n.substring(2);
        if (n.startsWith("q.")) return "query." + n.substring(2);
        if (n.startsWith("c.")) return "context." + n.substring(2);
        return n;
    }

    /** A variable read (unset reads as 0). */
    record Var(int slot) implements Expr {
        @Override public double eval(MolangContext ctx) { return ctx.get(slot); }
    }
}
