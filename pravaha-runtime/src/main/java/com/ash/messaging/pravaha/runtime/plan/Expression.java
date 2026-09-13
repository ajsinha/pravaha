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
package com.ash.messaging.pravaha.runtime.plan;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * A computed value: what a projection produces when it is not simply a column.
 *
 * <p>Until this existed, {@code SELECT amount * 2 FROM txn} was refused. That refusal was the right
 * call at the time -- a projection that silently produced the wrong column would be far worse -- but
 * it is a large hole in a SQL surface, and "compute it in the source query" is not an answer when
 * the source is a Kafka topic.
 *
 * <p><strong>Sealed and inspectable</strong>, exactly like {@link Predicate} and for the same
 * reason: a lambda can be <em>evaluated</em> but not <em>read</em>, and the code generator has to
 * read it to generate anything. That lesson was learned once already, in Wave 2, when {@code
 * Predicate} started life as a functional interface and had to be rebuilt as a tree.
 *
 * <p>Arithmetic is on {@code long} and {@code double} only. That is a real limitation rather than a
 * simplification: {@code DECIMAL} arithmetic needs the 128-bit path the row layout already has, and
 * doing it in {@code double} would produce a rounding error in somebody's ledger. A DECIMAL
 * expression is refused rather than approximated.
 */
public sealed interface Expression {

    /** The type this expression produces. */
    TypeName type();

    /** Evaluates over a row. */
    long evaluateLong(RowView row);

    /** Evaluates over a row, for the floating-point types. */
    double evaluateDouble(RowView row);

    /**
     * Evaluates over a row, for {@code STRING}.
     *
     * <p>A default that throws rather than a method every implementation must write, because most
     * expressions genuinely have no text value and an {@code ABS} that returned {@code "0"} to
     * satisfy an interface would be worse than one that refuses. Reaching this is a bug in the
     * compiler, not a bad query: an expression whose {@link #type()} is not {@code STRING} should
     * never be asked for its text, and the planner is what guarantees that.
     *
     * <p>Unlike the numeric evaluators this one allocates. The zero-copy path in {@link RowView} is
     * {@code getBytes}, and using it here would mean writing every function against UTF-8 slices
     * with a scratch buffer to build results in. That is the right destination and this is not it:
     * the interpreted pipeline already materialises strings to compare them, so this matches what
     * the engine does today rather than adding a new cost.
     */
    default String evaluateString(RowView row) {
        throw new IllegalStateException(
                describe() + " produces " + type() + ", not text, and nothing should be asking it for a string");
    }

    /** Whether this expression is null for the given row. */
    boolean isNull(RowView row);

    /** Renders the expression for {@code EXPLAIN}. */
    String describe();

    /**
     * A column read. The leaf of every expression, and the only one that can be null.
     *
     * <p>Carries the column's name purely so {@code EXPLAIN} can print {@code amount * 2 > 100}
     * rather than {@code $2 * 2 > 100}. Evaluation uses the ordinal; the name is never read on the
     * row path, and nothing should start depending on it there.
     */
    record Column(int ordinal, String name, TypeName type) implements Expression {

        @Override
        public long evaluateLong(RowView row) {
            return switch (type) {
                case INT8 -> row.getByte(ordinal);
                case INT16 -> row.getShort(ordinal);
                case INT32, DATE -> row.getInt(ordinal);
                case BOOLEAN -> row.getBoolean(ordinal) ? 1 : 0;
                default -> row.getLong(ordinal);
            };
        }

        @Override
        public double evaluateDouble(RowView row) {
            return switch (type) {
                case FLOAT32 -> row.getFloat(ordinal);
                case FLOAT64 -> row.getDouble(ordinal);
                default -> evaluateLong(row);
            };
        }

        @Override
        public String evaluateString(RowView row) {
            return row.getString(ordinal);
        }

        @Override
        public boolean isNull(RowView row) {
            return row.isNull(ordinal);
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /**
     * A numeric conversion.
     *
     * <p>Present because mixing types forces one: {@code rate * 2 > amount} over a DOUBLE rate and a
     * BIGINT amount arrives from Calcite with a CAST around the amount, and refusing it would refuse
     * the query for a reason that has nothing to do with what the user wrote.
     *
     * <p>Only numeric conversions. Narrowing is allowed and truncates towards zero, which is what
     * the SQL standard calls implementation-defined and what Java does anyway; what is not allowed
     * is a conversion that would silently change a value's meaning rather than its width.
     */
    record Cast(Expression source, TypeName type) implements Expression {

        @Override
        public long evaluateLong(RowView row) {
            return source.isFloatingPoint() ? (long) source.evaluateDouble(row) : source.evaluateLong(row);
        }

        @Override
        public double evaluateDouble(RowView row) {
            return source.isFloatingPoint() ? source.evaluateDouble(row) : source.evaluateLong(row);
        }

        @Override
        public boolean isNull(RowView row) {
            return source.isNull(row);
        }

        @Override
        public String describe() {
            // Deliberately invisible in EXPLAIN: the cast is Pravaha's, not the user's, and printing
            // it makes a plan harder to match against the query that produced it.
            return source.describe();
        }
    }

    /** A constant. */
    record Literal(long longValue, double doubleValue, String textValue, TypeName type, boolean isNull)
            implements Expression {

        public static Literal ofLong(long value) {
            return new Literal(value, value, null, TypeName.INT64, false);
        }

        public static Literal ofDouble(double value) {
            return new Literal((long) value, value, null, TypeName.FLOAT64, false);
        }

        public static Literal ofText(String value) {
            return new Literal(0, 0, value, TypeName.STRING, false);
        }

        public static Literal ofNull(TypeName type) {
            return new Literal(0, 0, null, type, true);
        }

        @Override
        public String evaluateString(RowView row) {
            return textValue;
        }

        @Override
        public long evaluateLong(RowView row) {
            return longValue;
        }

        @Override
        public double evaluateDouble(RowView row) {
            return doubleValue;
        }

        @Override
        public boolean isNull(RowView row) {
            return isNull;
        }

        @Override
        public String describe() {
            if (isNull) {
                return "NULL";
            }
            return switch (type) {
                // Quoted, so an EXPLAIN of `name = 'FLAGGED'` does not read as a column called
                // FLAGGED that nobody can find in the schema.
                case STRING -> "'" + textValue + "'";
                case FLOAT64 -> String.valueOf(doubleValue);
                default -> String.valueOf(longValue);
            };
        }
    }

    /**
     * {@code CASE WHEN … THEN … ELSE … END}.
     *
     * <p>Two branches rather than a list, because Calcite hands over a chain and a chain of two-way
     * choices evaluates identically while staying a shape with one obvious meaning. {@code CASE WHEN
     * a THEN 1 WHEN b THEN 2 ELSE 3 END} becomes {@code Case(a, 1, Case(b, 2, 3))}.
     *
     * <p>Only the branch that is taken is evaluated, which is not an optimisation: {@code CASE WHEN
     * n = 0 THEN 0 ELSE total / n END} divides by zero if both arms are evaluated, and a reader is
     * entitled to assume the guard guards.
     */
    record Case(Predicate when, Expression then, Expression otherwise) implements Expression {

        public Case {
            // A null branch takes the other's type. `CASE WHEN x THEN 1 END` has no ELSE in the
            // SQL, and Calcite supplies a null one typed however it likes -- so comparing types
            // strictly would refuse the commonest CASE there is. A null is a null of whatever the
            // column holds.
            if (isNullLiteral(otherwise)) {
                otherwise = Literal.ofNull(then.type());
            } else if (isNullLiteral(then)) {
                then = Literal.ofNull(otherwise.type());
            }
            if (then.type() != otherwise.type()) {
                throw new IllegalArgumentException("a CASE must produce one type, and this one produces "
                        + then.type() + " on the THEN branch and " + otherwise.type() + " on the ELSE. A row "
                        + "whose type depends on its own values has no schema.");
            }
        }

        private static boolean isNullLiteral(Expression expression) {
            return expression instanceof Literal literal && literal.isNull();
        }

        @Override
        public TypeName type() {
            return then.type();
        }

        @Override
        public long evaluateLong(RowView row) {
            return when.test(row) ? then.evaluateLong(row) : otherwise.evaluateLong(row);
        }

        @Override
        public double evaluateDouble(RowView row) {
            return when.test(row) ? then.evaluateDouble(row) : otherwise.evaluateDouble(row);
        }

        @Override
        public String evaluateString(RowView row) {
            return when.test(row) ? then.evaluateString(row) : otherwise.evaluateString(row);
        }

        @Override
        public boolean isNull(RowView row) {
            return when.test(row) ? then.isNull(row) : otherwise.isNull(row);
        }

        @Override
        public String describe() {
            return "CASE WHEN " + when.describe() + " THEN " + then.describe() + " ELSE " + otherwise.describe()
                    + " END";
        }
    }

    /**
     * {@code UPPER}, {@code LOWER}, {@code TRIM}: one string in, one string out.
     *
     * <p>{@code TRIM} removes spaces and only spaces. SQL's default trim character is {@code ' '},
     * not "whitespace" -- Java's {@code strip()} would also take tabs and newlines, which is a
     * different function wearing the same name. A query that means to strip tabs can say so once
     * the {@code TRIM(… FROM …)} form is supported; it is refused today rather than approximated.
     *
     * <p>Case conversion uses the JVM's default locale deliberately left alone: {@link
     * String#toUpperCase()} is locale-sensitive, and in a Turkish locale {@code UPPER('i')} is
     * {@code 'İ'} rather than {@code 'I'}. That is correct for text and wrong for an engine whose
     * answer must not depend on which machine a lane happens to run on, so both use {@link
     * java.util.Locale#ROOT}.
     */
    record TextFunction(TextOp operation, Expression argument) implements Expression {

        public TextFunction {
            if (argument.type() != TypeName.STRING) {
                throw new IllegalArgumentException(
                        operation + " takes text, and this argument produces " + argument.type());
            }
        }

        @Override
        public TypeName type() {
            return TypeName.STRING;
        }

        @Override
        public String evaluateString(RowView row) {
            String value = argument.evaluateString(row);
            return switch (operation) {
                case UPPER -> value.toUpperCase(java.util.Locale.ROOT);
                case LOWER -> value.toLowerCase(java.util.Locale.ROOT);
                case TRIM -> trimSpaces(value);
            };
        }

        private static String trimSpaces(String value) {
            int start = 0;
            int end = value.length();
            while (start < end && value.charAt(start) == ' ') {
                start++;
            }
            while (end > start && value.charAt(end - 1) == ' ') {
                end--;
            }
            return value.substring(start, end);
        }

        @Override
        public long evaluateLong(RowView row) {
            throw numeric();
        }

        @Override
        public double evaluateDouble(RowView row) {
            throw numeric();
        }

        private IllegalStateException numeric() {
            return new IllegalStateException(describe() + " produces text, not a number");
        }

        @Override
        public boolean isNull(RowView row) {
            return argument.isNull(row);
        }

        @Override
        public String describe() {
            return operation + "(" + argument.describe() + ")";
        }
    }

    /** The text functions taking exactly one argument. */
    enum TextOp {
        UPPER,
        LOWER,
        TRIM
    }

    /**
     * {@code ||}, and {@code CONCAT} which Calcite rewrites into it.
     *
     * <p>A list rather than a pair, because {@code a || b || c} arrives as a chain and flattening it
     * builds the result in one pass through one buffer instead of allocating an intermediate string
     * per operator.
     *
     * <p><strong>Null concatenated with anything is null</strong>, which surprises people who expect
     * it to behave like an empty string. It is the SQL standard's rule and the reason {@code
     * first_name || ' ' || last_name} produces null for a row with no last name rather than a name
     * with a trailing space. Use {@code CASE WHEN last_name IS NULL THEN … END} to choose otherwise.
     */
    record Concat(java.util.List<Expression> parts) implements Expression {

        public Concat {
            if (parts.size() < 2) {
                throw new IllegalArgumentException("a concatenation needs at least two parts");
            }
            for (Expression part : parts) {
                if (part.type() != TypeName.STRING) {
                    throw new IllegalArgumentException("|| joins text, and one side produces " + part.type()
                            + ". Wrap it in CAST(… AS VARCHAR) if that is what you meant");
                }
            }
            parts = java.util.List.copyOf(parts);
        }

        @Override
        public TypeName type() {
            return TypeName.STRING;
        }

        @Override
        public String evaluateString(RowView row) {
            StringBuilder joined = new StringBuilder();
            for (Expression part : parts) {
                joined.append(part.evaluateString(row));
            }
            return joined.toString();
        }

        @Override
        public long evaluateLong(RowView row) {
            throw new IllegalStateException(describe() + " produces text, not a number");
        }

        @Override
        public double evaluateDouble(RowView row) {
            throw new IllegalStateException(describe() + " produces text, not a number");
        }

        @Override
        public boolean isNull(RowView row) {
            for (Expression part : parts) {
                if (part.isNull(row)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String describe() {
            return parts.stream().map(Expression::describe).collect(java.util.stream.Collectors.joining(" || "));
        }
    }

    /**
     * {@code SUBSTRING(s FROM start)} and {@code SUBSTRING(s FROM start FOR length)}.
     *
     * <p>Positions are 1-based and counted in <em>code points</em>, not in Java {@code char}s.
     * Counting chars is the one-line version and it cuts a surrogate pair in half: {@code
     * SUBSTRING(emoji FROM 1 FOR 1)} would return half of an emoji, which is not a string at all.
     * The cost is an {@code offsetByCodePoints} rather than an index, paid only by this function.
     *
     * <p>A start below 1 is not an error. The standard defines the result as the characters between
     * {@code start} and {@code start + length} that actually exist, so {@code SUBSTRING(s FROM -1
     * FOR 4)} returns the first two characters: positions -1 and 0 contribute nothing. Clamping
     * start to 1 instead -- the obvious-looking fix -- would return four characters and quietly
     * disagree with every other database.
     */
    record Substring(Expression source, Expression start, Expression length) implements Expression {

        public Substring {
            if (source.type() != TypeName.STRING) {
                throw new IllegalArgumentException("SUBSTRING takes text, and this argument produces " + source.type());
            }
        }

        /** The {@code FROM start} form, running to the end of the string. */
        public static Substring toEnd(Expression source, Expression start) {
            return new Substring(source, start, null);
        }

        @Override
        public TypeName type() {
            return TypeName.STRING;
        }

        @Override
        public String evaluateString(RowView row) {
            String value = source.evaluateString(row);
            int total = value.codePointCount(0, value.length());
            long from = start.evaluateLong(row);
            // The window in 1-based positions, half-open: [from, until). Computed in long so that
            // a huge length cannot overflow into a negative and turn a valid query into an empty
            // string.
            long until = length == null ? total + 1L : from + Math.max(0L, length.evaluateLong(row));
            long firstPosition = Math.max(1L, from);
            long lastPosition = Math.min(total + 1L, until);
            if (firstPosition >= lastPosition) {
                return "";
            }
            int begin = value.offsetByCodePoints(0, (int) (firstPosition - 1));
            int end = value.offsetByCodePoints(0, (int) (lastPosition - 1));
            return value.substring(begin, end);
        }

        @Override
        public long evaluateLong(RowView row) {
            throw new IllegalStateException(describe() + " produces text, not a number");
        }

        @Override
        public double evaluateDouble(RowView row) {
            throw new IllegalStateException(describe() + " produces text, not a number");
        }

        @Override
        public boolean isNull(RowView row) {
            return source.isNull(row) || start.isNull(row) || (length != null && length.isNull(row));
        }

        @Override
        public String describe() {
            return "SUBSTRING(" + source.describe() + " FROM " + start.describe()
                    + (length == null ? "" : " FOR " + length.describe()) + ")";
        }
    }

    /** A one-argument numeric function. */
    record Unary(Function function, Expression argument) implements Expression {

        @Override
        public TypeName type() {
            // The argument's type, unchanged. FLOOR of an integer is that integer and of a double
            // is a double; ABS likewise. Widening here would silently change a column's type on
            // the way through a function that was asked to do arithmetic, not conversion.
            return argument.type();
        }

        @Override
        public long evaluateLong(RowView row) {
            long value = argument.evaluateLong(row);
            return switch (function) {
                // Math.abs(Long.MIN_VALUE) is Long.MIN_VALUE -- negative, from a function whose
                // whole job is to return something that is not. Refused rather than returned,
                // because an absolute value that is negative is not an approximation of the right
                // answer, it is the wrong one wearing the right type.
                case ABS -> {
                    if (value == Long.MIN_VALUE) {
                        throw new ArithmeticException(
                                "ABS(" + value + ") has no representable result: the range of a 64-bit "
                                        + "integer is asymmetric, so the magnitude of its smallest value is "
                                        + "one larger than its largest.");
                    }
                    yield Math.abs(value);
                }
                // Already whole. Returning it unchanged rather than round-tripping through a double,
                // which loses precision above 2^53 and would make FLOOR of a large id a different id.
                case FLOOR, CEIL, ROUND -> value;
            };
        }

        @Override
        public double evaluateDouble(RowView row) {
            double value = argument.evaluateDouble(row);
            return switch (function) {
                case ABS -> Math.abs(value);
                case FLOOR -> Math.floor(value);
                case CEIL -> Math.ceil(value);
                // Half away from zero, which is what SQL means by ROUND. Math.rint is half-to-even
                // -- banker's rounding -- so it made ROUND(2.5) into 2 and ROUND(-2.5) into -2,
                // disagreeing with Calcite, Postgres, MySQL and Oracle. Every value it produced was
                // plausible, which is why nobody notices until an invoice is out by a penny.
                // BigDecimal, not floor(abs(x)+0.5). That idiom -- mine -- is wrong three ways,
                // each computed rather than guessed: ROUND(0.49999999999999994) gives 1.0 because
                // 0.5-2^-54 + 0.5 ties up to exactly 1.0; ROUND(4503599627370497.0) changes a whole
                // number because x+0.5 is unrepresentable above 2^52; and ROUND(-0.4) yields -0.0,
                // which Double.compare says is less than zero.
                case ROUND ->
                    Double.isFinite(value)
                            ? java.math.BigDecimal.valueOf(value)
                                    .setScale(0, java.math.RoundingMode.HALF_UP)
                                    .doubleValue()
                            : value;
            };
        }

        @Override
        public boolean isNull(RowView row) {
            // Null in, null out. ABS(NULL) is NULL and not zero, which is the difference between
            // "we do not know" and "it is nothing".
            return argument.isNull(row);
        }

        @Override
        public String describe() {
            return function.name() + "(" + argument.describe() + ")";
        }
    }

    /** The one-argument numeric functions this engine evaluates. */
    enum Function {
        ABS,
        FLOOR,
        CEIL,
        ROUND
    }

    enum Operator {
        ADD("+"),
        SUBTRACT("-"),
        MULTIPLY("*"),
        DIVIDE("/"),
        MODULO("%");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /**
     * A binary arithmetic expression.
     *
     * <p>Null propagates: if either side is null the result is null, which is SQL's rule and not
     * Java's. Getting this wrong produces zeroes where nulls belong, and a SUM over those zeroes is
     * a number that looks entirely reasonable.
     */
    record Arithmetic(Expression left, Operator operator, Expression right, TypeName type) implements Expression {

        @Override
        public long evaluateLong(RowView row) {
            long l = left.evaluateLong(row);
            long r = right.evaluateLong(row);
            return switch (operator) {
                case ADD -> Math.addExact(l, r);
                case SUBTRACT -> Math.subtractExact(l, r);
                case MULTIPLY -> Math.multiplyExact(l, r);
                // Integer division by zero is an exception in Java and NULL in SQL. Neither is
                // obviously right for a stream, and throwing is the one that cannot be mistaken for
                // an answer -- the record goes to the dead-letter queue with the reason.
                case DIVIDE -> r == 0 ? divideByZero() : l / r;
                case MODULO -> r == 0 ? divideByZero() : l % r;
            };
        }

        private static long divideByZero() {
            throw new ArithmeticException("division by zero in a projection; the record is routed to the DLQ "
                    + "rather than given a value that could be mistaken for an answer");
        }

        @Override
        public double evaluateDouble(RowView row) {
            double l = left.evaluateDouble(row);
            double r = right.evaluateDouble(row);
            return switch (operator) {
                case ADD -> l + r;
                case SUBTRACT -> l - r;
                case MULTIPLY -> l * r;
                // Floating point division by zero is infinity rather than an error, which is IEEE's
                // answer and stays IEEE's answer here: silently converting it would hide a data
                // problem behind an arithmetic one.
                case DIVIDE -> l / r;
                case MODULO -> l % r;
            };
        }

        @Override
        public boolean isNull(RowView row) {
            return left.isNull(row) || right.isNull(row);
        }

        @Override
        public String describe() {
            return "(" + left.describe() + " " + operator.symbol() + " " + right.describe() + ")";
        }
    }

    /** Whether this expression produces a floating-point value. */
    default boolean isFloatingPoint() {
        return type() == TypeName.FLOAT32 || type() == TypeName.FLOAT64;
    }
}
