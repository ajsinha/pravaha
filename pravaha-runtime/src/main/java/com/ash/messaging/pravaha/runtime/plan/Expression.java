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
     * <p>Only numeric conversions. A floating-point value narrowed to an integer truncates towards
     * zero, and must be a finite number within the target's range: {@code NaN}, an infinity or
     * {@code 1e300} has no integer answer, and Java's {@code (long)} would make them {@code 0} and
     * {@code Long.MAX_VALUE} (NARROWCAST-1). An integer narrowed to {@code INT}, {@code SMALLINT} or
     * {@code TINYINT} must fit (NARROWINT-1), because its low bits are a different number. A finite
     * {@code DOUBLE} narrowed to {@code REAL} must not become an infinity. Each is an overflow, routed
     * as a {@code BIGINT} one is -- a conversion that would silently change a value's meaning rather
     * than its width is never answered.
     */
    record Cast(Expression source, TypeName type) implements Expression {

        @Override
        public long evaluateLong(RowView row) {
            long value = source.isFloatingPoint()
                    ? floatingToLong(source.evaluateDouble(row), type, this)
                    : source.evaluateLong(row);
            // A narrowing to INT, SMALLINT or TINYINT of a value outside the target's range has no
            // answer, so it is an overflow like any other (NARROWINT-1) rather than the value's low
            // bits written by the projection while a filter compared the whole of it.
            return fitNarrow(value, type, this);
        }

        @Override
        public double evaluateDouble(RowView row) {
            if (!isFloatingPoint()) {
                // An integer target read as a double (CAST(d AS BIGINT) * 1.5) is the integer --
                // truncated and range-checked -- not the floating source passed through unchanged.
                return evaluateLong(row);
            }
            double value = source.isFloatingPoint() ? source.evaluateDouble(row) : source.evaluateLong(row);
            if (type == TypeName.FLOAT32 && Double.isFinite(value) && Math.abs(value) > Float.MAX_VALUE) {
                throw new ArithmeticException("REAL overflow: " + source.describe() + " is " + value
                        + ", outside REAL's range; refused rather than published as an infinity. "
                        + "Keep it a DOUBLE.");
            }
            return value;
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

        /**
         * A boolean literal, carried as 1 and 0.
         *
         * <p>Typed BOOLEAN rather than INT64, because the type is what the projection writes by:
         * {@code SELECT TRUE} has to reach the row as a boolean column, not as the number one.
         */
        public static Literal ofBoolean(boolean value) {
            return new Literal(value ? 1 : 0, value ? 1 : 0, null, TypeName.BOOLEAN, false);
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
                    // The advice here used to be "wrap it in CAST(… AS VARCHAR)", which is refused
                    // by the same compiler for the same reason (TY-23): there is no number-to-text
                    // conversion anywhere in this engine, so sending the reader to write one sends
                    // them in a circle.
                    throw new IllegalArgumentException("|| joins text, and one side produces " + part.type()
                            + ". This engine has no conversion from a number to text -- not through CAST"
                            + " either -- so format the value where the text is assembled");
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
            // The window in 1-based positions, half-open: [from, until). Finding TY-22: the comment
            // here used to claim long arithmetic made this overflow-safe, and it did not. `FOR
            // 9223372036854775807` -- the way a generated query spells "to the end" -- made `1 +
            // Long.MAX_VALUE` wrap to Long.MIN_VALUE, so the range came out empty and the whole
            // value was silently replaced by "". Saturating at Long.MAX_VALUE is the arithmetic the
            // old comment described: a window that runs past the end of the string is clamped to
            // the end of the string one line below, which is what the standard asks for.
            long span = length == null ? Long.MAX_VALUE : Math.max(0L, length.evaluateLong(row));
            long until = from > Long.MAX_VALUE - span ? Long.MAX_VALUE : from + span;
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
                    // The same asymmetry at every width (NARROWINT-1): ABS of an INT at
                    // -2147483648 is 2147483648, which is not an INT.
                    yield fitNarrow(Math.abs(value), type(), this);
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
            long result =
                    switch (operator) {
                        case ADD -> Math.addExact(l, r);
                        case SUBTRACT -> Math.subtractExact(l, r);
                        case MULTIPLY -> Math.multiplyExact(l, r);
                        // Integer division by zero is an exception in Java and NULL in SQL. Neither
                        // is obviously right for a stream, and throwing is the one that cannot be
                        // mistaken for an answer -- the record goes to the dead-letter queue with
                        // the reason.
                        case DIVIDE -> r == 0 ? divideByZero() : divideExact(l, r);
                        // Long.MIN_VALUE % -1 is 0 in Java and in arithmetic alike: no overflow.
                        case MODULO -> r == 0 ? divideByZero() : l % r;
                    };
            // NARROWINT-1: the operands are evaluated in 64 bits, so an INT, SMALLINT or TINYINT
            // result past its type's range used to be exact here and wrapped where the projection
            // wrote it -- 2e9 * 2 published as -294967296 while a filter on the same expression
            // compared 4e9. SQL types INT * INT as INT, so the result is checked against that range
            // here, once, for every consumer: an overflow exactly as a BIGINT one is.
            return fitNarrow(result, type, this);
        }

        /**
         * {@code l / r} for a non-zero {@code r}, refusing the one quotient that does not fit 64 bits
         * (DIVMIN-1): {@code Long.MIN_VALUE / -1} is {@code 2^63}, and Java answers
         * {@code Long.MIN_VALUE} where {@code + - *} use {@code Math.*Exact}. A narrower type's
         * {@code MIN / -1} fits 64 bits and is refused by {@link #fitNarrow} as its own overflow.
         */
        private long divideExact(long l, long r) {
            if (l == Long.MIN_VALUE && r == -1) {
                throw new ArithmeticException("BIGINT overflow: " + describe() + " is 9223372036854775808, "
                        + "outside BIGINT's range; refused rather than published as -9223372036854775808");
            }
            return l / r;
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

    /**
     * {@code DATE_FORMAT(ts, 'pattern')}: an instant rendered as text in UTC.
     *
     * <p>The pattern is a literal, compiled once at registration, so a malformed pattern is refused
     * there rather than on the first row. Patterns are {@link java.time.format.DateTimeFormatter}'s,
     * which agree with {@code SimpleDateFormat}'s for the letters Nexmark and Flink users write
     * ({@code yyyy-MM-dd}, {@code HH:mm:ss}). Rendered in UTC because a timestamp here is an instant
     * on the UTC timeline (design section 15.1) and there is no session time zone to render it in;
     * choosing the JVM's zone instead would make the same query answer differently on two nodes.
     *
     * <p>Null in, null out.
     */
    record DateFormat(Expression source, String pattern, java.time.format.DateTimeFormatter formatter)
            implements Expression {

        public DateFormat {
            if (source.type() != TypeName.TIMESTAMP_LTZ) {
                throw new IllegalArgumentException(
                        "DATE_FORMAT takes a timestamp, and this argument produces " + source.type());
            }
        }

        /** Compiles the pattern, or throws {@link IllegalArgumentException} naming what is wrong. */
        public static DateFormat of(Expression source, String pattern) {
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern(
                            pattern, java.util.Locale.ROOT)
                    .withZone(java.time.ZoneOffset.UTC);
            // Formatting one instant proves the pattern asks for nothing an instant lacks. A pattern
            // can parse and still name a field no instant carries, and that would otherwise fail on
            // the first row instead of at registration.
            try {
                formatter.format(java.time.Instant.EPOCH);
            } catch (java.time.DateTimeException unformattable) {
                throw new IllegalArgumentException(unformattable.getMessage(), unformattable);
            }
            return new DateFormat(source, pattern, formatter);
        }

        @Override
        public TypeName type() {
            return TypeName.STRING;
        }

        @Override
        public String evaluateString(RowView row) {
            long nanos = source.evaluateLong(row);
            return formatter.format(java.time.Instant.ofEpochSecond(
                    Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L)));
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
            return source.isNull(row);
        }

        @Override
        public String describe() {
            return "DATE_FORMAT(" + source.describe() + ", '" + pattern + "')";
        }

        // Equality on the pattern text, not the formatter. DateTimeFormatter inherits identity
        // equality, and two registrations of one query that compared unequal would be given
        // separate computations -- the sharing this engine exists to do (see Predicate.Like).
        @Override
        public boolean equals(Object other) {
            return other instanceof DateFormat that && source.equals(that.source) && pattern.equals(that.pattern);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(source, pattern);
        }
    }

    /**
     * {@code REGEXP_EXTRACT(s, 'regex'[, group])}: the given group of the first match, or null.
     *
     * <p>Flink's semantics, which is where Nexmark's queries come from: the group defaults to 0, the
     * whole match; a string with no match produces null; null in any argument produces null. The
     * expression and the group are literals checked at registration, so a regex that does not
     * compile, or a group the regex does not have, is refused there. A group that exists but took no
     * part in the match is null, which is what {@link java.util.regex.Matcher#group(int)} says and
     * what Flink returns.
     *
     * <p>Because a missing match is null, {@link #isNull} has to run the match, and a present value
     * is matched twice. Caching the last row's result instead would be wrong the first time a caller
     * evaluated two rows in a different order.
     */
    record RegexpExtract(Expression source, java.util.regex.Pattern regex, int group) implements Expression {

        public RegexpExtract {
            if (source.type() != TypeName.STRING) {
                throw new IllegalArgumentException(
                        "REGEXP_EXTRACT takes text, and this argument produces " + source.type());
            }
            int groups = regex.matcher("").groupCount();
            if (group < 0 || group > groups) {
                throw new IllegalArgumentException("REGEXP_EXTRACT asks for group " + group + " of '" + regex.pattern()
                        + "', which has " + groups + " group(s) besides the whole match, 0");
            }
        }

        private String extract(RowView row) {
            if (source.isNull(row)) {
                return null;
            }
            java.util.regex.Matcher matcher = regex.matcher(source.evaluateString(row));
            return matcher.find() ? matcher.group(group) : null;
        }

        @Override
        public TypeName type() {
            return TypeName.STRING;
        }

        @Override
        public String evaluateString(RowView row) {
            return extract(row);
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
            return extract(row) == null;
        }

        @Override
        public String describe() {
            return "REGEXP_EXTRACT(" + source.describe() + ", '" + regex.pattern() + "', " + group + ")";
        }

        // Equality on the expression's text, not the compiled Pattern, which inherits identity
        // equality; see DateFormat.equals for why that matters.
        @Override
        public boolean equals(Object other) {
            return other instanceof RegexpExtract that
                    && source.equals(that.source)
                    && regex.pattern().equals(that.regex.pattern())
                    && group == that.group;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(source, regex.pattern(), group);
        }
    }

    /**
     * {@code SPLIT_INDEX(s, 'delimiter', n)}: field {@code n}, counting from zero, of {@code s} split
     * on the whole delimiter.
     *
     * <p>Flink's semantics: every field is kept, empty ones included, so {@code
     * SPLIT_INDEX('http://a/b', '/', 2)} is {@code 'a'} -- field 1 is the empty string between the two
     * slashes. An index past the last field produces null, as does null in any argument and an empty
     * string, which has no fields. The delimiter and the index are literals checked at registration:
     * an empty delimiter splits nothing and a negative index names no field, so both are refused
     * there rather than answered with null on every row.
     */
    record SplitIndex(Expression source, String delimiter, int index) implements Expression {

        public SplitIndex {
            if (source.type() != TypeName.STRING) {
                throw new IllegalArgumentException(
                        "SPLIT_INDEX takes text, and this argument produces " + source.type());
            }
            if (delimiter.isEmpty()) {
                throw new IllegalArgumentException("SPLIT_INDEX's delimiter is empty, which splits nothing");
            }
            if (index < 0) {
                throw new IllegalArgumentException("SPLIT_INDEX's index is " + index
                        + "; fields are counted from 0, so a negative index names none");
            }
        }

        private String field(RowView row) {
            if (source.isNull(row)) {
                return null;
            }
            String value = source.evaluateString(row);
            if (value.isEmpty()) {
                return null;
            }
            int from = 0;
            for (int field = 0; ; field++) {
                int at = value.indexOf(delimiter, from);
                if (field == index) {
                    return at < 0 ? value.substring(from) : value.substring(from, at);
                }
                if (at < 0) {
                    return null;
                }
                from = at + delimiter.length();
            }
        }

        @Override
        public TypeName type() {
            return TypeName.STRING;
        }

        @Override
        public String evaluateString(RowView row) {
            return field(row);
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
            return field(row) == null;
        }

        @Override
        public String describe() {
            return "SPLIT_INDEX(" + source.describe() + ", '" + delimiter + "', " + index + ")";
        }
    }

    /**
     * Evaluates over a row, for {@code DECIMAL}.
     *
     * <p>A default that throws, for the reason {@link #evaluateString} gives: only the decimal
     * expressions have a decimal value, and the compiler guarantees nothing else is asked for one.
     * This allocates a {@code BigDecimal} per call. Exactness is the requirement here and the
     * two-limb arithmetic that would avoid the allocation is not built; the cost is paid only by a
     * query that computes with decimals.
     */
    default java.math.BigDecimal evaluateDecimal(RowView row) {
        throw new IllegalStateException(
                describe() + " produces " + type() + ", not a decimal, and nothing should be asking it for one");
    }

    /** Any exact numeric expression's value as a decimal: a DECIMAL's own, or an integer's, widened. */
    static java.math.BigDecimal decimalOf(Expression expression, RowView row) {
        return expression.type() == TypeName.DECIMAL
                ? expression.evaluateDecimal(row)
                : java.math.BigDecimal.valueOf(expression.evaluateLong(row));
    }

    /** A DECIMAL column, read at the scale its schema declares. */
    record DecimalColumn(int ordinal, String name, int scale) implements Expression {

        @Override
        public TypeName type() {
            return TypeName.DECIMAL;
        }

        @Override
        public java.math.BigDecimal evaluateDecimal(RowView row) {
            return com.ash.messaging.pravaha.common.row.Decimals.toBigDecimal(
                    row.getDecimalHigh(ordinal), row.getDecimalLow(ordinal), scale);
        }

        @Override
        public long evaluateLong(RowView row) {
            throw new IllegalStateException(describe() + " is a decimal; read it with evaluateDecimal");
        }

        @Override
        public double evaluateDouble(RowView row) {
            throw new IllegalStateException(describe() + " is a decimal; read it with evaluateDecimal");
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

    /** A DECIMAL literal, held exactly as it was written: {@code 0.908} is 908 at scale 3. */
    record DecimalLiteral(java.math.BigDecimal value) implements Expression {

        @Override
        public TypeName type() {
            return TypeName.DECIMAL;
        }

        @Override
        public java.math.BigDecimal evaluateDecimal(RowView row) {
            return value;
        }

        @Override
        public long evaluateLong(RowView row) {
            throw new IllegalStateException(describe() + " is a decimal; read it with evaluateDecimal");
        }

        @Override
        public double evaluateDouble(RowView row) {
            throw new IllegalStateException(describe() + " is a decimal; read it with evaluateDecimal");
        }

        @Override
        public boolean isNull(RowView row) {
            return false;
        }

        @Override
        public String describe() {
            return value.toPlainString();
        }
    }

    /**
     * Exact arithmetic on decimals: {@code +}, {@code -} and {@code *}, with the result held at the
     * precision and scale SQL's rules give it.
     *
     * <p>The compiler admits a call here only when the result type's scale can hold the exact
     * answer: {@code max(s1, s2)} for a sum or difference, {@code s1 + s2} for a product. So no row
     * is ever rounded. What can still happen is that a row's answer has more integer digits than
     * the type allows -- DECIMAL(5, 2) holds 999.99 -- and that row is refused with an {@link
     * ArithmeticException}, which sends it to the dead-letter queue with the reason, exactly as a
     * 64-bit integer overflow does. Division is not here: a quotient is rarely exact at any scale,
     * so it cannot be computed without rounding, and the compiler refuses it by name.
     *
     * <p>Operands may be integers as well as decimals; an integer is widened exactly, at scale 0.
     */
    record DecimalArithmetic(Expression left, Operator operator, Expression right, int precision, int scale)
            implements Expression {

        public DecimalArithmetic {
            if (operator != Operator.ADD && operator != Operator.SUBTRACT && operator != Operator.MULTIPLY) {
                throw new IllegalArgumentException("decimal " + operator.symbol()
                        + " cannot be computed exactly, so it is not a decimal arithmetic this evaluates");
            }
            for (Expression side : new Expression[] {left, right}) {
                if (side.isFloatingPoint() || side.type() == TypeName.STRING) {
                    throw new IllegalArgumentException(
                            "decimal arithmetic takes decimals and integers, and one side produces " + side.type());
                }
            }
        }

        @Override
        public TypeName type() {
            return TypeName.DECIMAL;
        }

        @Override
        public java.math.BigDecimal evaluateDecimal(RowView row) {
            java.math.BigDecimal l = decimalOf(left, row);
            java.math.BigDecimal r = decimalOf(right, row);
            java.math.BigDecimal exact =
                    switch (operator) {
                        case ADD -> l.add(r);
                        case SUBTRACT -> l.subtract(r);
                        case MULTIPLY -> l.multiply(r);
                        default -> throw new IllegalStateException("unreachable: " + operator);
                    };
            // UNNECESSARY: the compiler proved the scale suffices, and if that proof were ever wrong
            // this throws rather than rounds.
            java.math.BigDecimal held = exact.setScale(scale, java.math.RoundingMode.UNNECESSARY);
            if (held.precision() - held.scale() > precision - scale) {
                throw new ArithmeticException(
                        describe() + " is " + held.toPlainString() + ", which does not fit DECIMAL("
                                + precision + ", " + scale + "); the record is routed to the DLQ rather than given "
                                + "a value that is not the answer");
            }
            return held;
        }

        @Override
        public long evaluateLong(RowView row) {
            throw new IllegalStateException(describe() + " is a decimal; read it with evaluateDecimal");
        }

        @Override
        public double evaluateDouble(RowView row) {
            throw new IllegalStateException(describe() + " is a decimal; read it with evaluateDecimal");
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

    /**
     * A cast to {@code DECIMAL(precision, scale)} from an integer or a decimal whose scale is no
     * larger -- a conversion that never rounds, which the compiler checks. A value with more integer
     * digits than the target allows is refused per row, as {@link DecimalArithmetic} refuses one.
     */
    record DecimalRescale(Expression source, int precision, int scale) implements Expression {

        public DecimalRescale {
            if (source.isFloatingPoint() || source.type() == TypeName.STRING) {
                throw new IllegalArgumentException(
                        "a cast to DECIMAL takes a decimal or an integer, and this produces " + source.type());
            }
        }

        @Override
        public TypeName type() {
            return TypeName.DECIMAL;
        }

        @Override
        public java.math.BigDecimal evaluateDecimal(RowView row) {
            java.math.BigDecimal held = decimalOf(source, row).setScale(scale, java.math.RoundingMode.UNNECESSARY);
            if (held.precision() - held.scale() > precision - scale) {
                throw new ArithmeticException(held.toPlainString() + " does not fit DECIMAL(" + precision + ", " + scale
                        + "); the record is routed to the DLQ rather than given a value that is not the answer");
            }
            return held;
        }

        @Override
        public long evaluateLong(RowView row) {
            throw new IllegalStateException(describe() + " is a decimal; read it with evaluateDecimal");
        }

        @Override
        public double evaluateDouble(RowView row) {
            throw new IllegalStateException(describe() + " is a decimal; read it with evaluateDecimal");
        }

        @Override
        public boolean isNull(RowView row) {
            return source.isNull(row);
        }

        @Override
        public String describe() {
            return "CAST(" + source.describe() + " AS DECIMAL(" + precision + ", " + scale + "))";
        }
    }

    /**
     * An explicit {@code CAST(decimal AS DOUBLE)}. Approximate by request: the query asked for a
     * double, and a double is the nearest one to the decimal's exact value.
     */
    record DecimalToDouble(Expression source) implements Expression {

        @Override
        public TypeName type() {
            return TypeName.FLOAT64;
        }

        @Override
        public double evaluateDouble(RowView row) {
            return source.evaluateDecimal(row).doubleValue();
        }

        @Override
        public long evaluateLong(RowView row) {
            throw new IllegalStateException(describe() + " produces a double");
        }

        @Override
        public boolean isNull(RowView row) {
            return source.isNull(row);
        }

        @Override
        public String describe() {
            return "CAST(" + source.describe() + " AS DOUBLE)";
        }
    }

    /** Whether this expression produces a floating-point value. */
    default boolean isFloatingPoint() {
        return type() == TypeName.FLOAT32 || type() == TypeName.FLOAT64;
    }

    /**
     * {@code value}, if it is within {@code type}'s range; an {@link ArithmeticException} naming the
     * expression and the range if {@code type} is {@code INT}, {@code SMALLINT} or {@code TINYINT}
     * and it is not (NARROWINT-1). Every other type passes through: {@code BIGINT} is checked by the
     * {@code *Exact} arithmetic that produced the value.
     *
     * <p>An overflow, not a wrap and not a silent widening: SQL gives {@code INT * INT} the type
     * {@code INT}, and a value outside it has no answer of that type. It is routed as a {@code BIGINT}
     * overflow is -- the query stops {@code PRV-8003}, or the row is dead-lettered where that applies
     * -- and a filter on the expression meets the same exception as the projection of it, so the two
     * cannot disagree. Widening the operands ({@code CAST(i AS BIGINT) * 2}) is how to ask for the
     * 64-bit answer.
     */
    /**
     * A floating-point {@code value} converted to an integer for a {@code CAST} to {@code type},
     * truncated towards zero; an {@link ArithmeticException} if it has no integer answer
     * (NARROWCAST-1). {@code NaN} and the infinities are not numbers an integer can hold, and a
     * finite value at or beyond {@code 2^63} is outside {@code BIGINT}: Java's {@code (long)} makes
     * them {@code 0}, {@code Long.MAX_VALUE} and {@code Long.MIN_VALUE}, which a filter then
     * compares as if they were the value. The narrower targets are checked by {@link #fitNarrow}.
     */
    static long floatingToLong(double value, TypeName type, Expression where) {
        // -2^63 is exactly representable and is Long.MIN_VALUE; 2^63 is not a long.
        if (Double.isNaN(value) || value >= 0x1p63 || value < -0x1p63) {
            String name =
                    switch (type) {
                        case INT32 -> "INT";
                        case INT16 -> "SMALLINT";
                        case INT8 -> "TINYINT";
                        default -> "BIGINT";
                    };
            throw new ArithmeticException(name + " overflow: CAST of " + where.describe() + " is " + value
                    + ", which has no " + name + " value; refused rather than converted to a number it is not. "
                    + "Filter the row out (WHERE " + where.describe() + " BETWEEN ...) or keep it a DOUBLE.");
        }
        return (long) value;
    }

    static long fitNarrow(long value, TypeName type, Expression where) {
        long min;
        long max;
        String name;
        switch (type) {
            case INT32 -> {
                min = Integer.MIN_VALUE;
                max = Integer.MAX_VALUE;
                name = "INT";
            }
            case INT16 -> {
                min = Short.MIN_VALUE;
                max = Short.MAX_VALUE;
                name = "SMALLINT";
            }
            case INT8 -> {
                min = Byte.MIN_VALUE;
                max = Byte.MAX_VALUE;
                name = "TINYINT";
            }
            default -> {
                return value;
            }
        }
        if (value < min || value > max) {
            throw new ArithmeticException(name + " overflow: " + where.describe() + " is " + value
                    + ", outside " + name + "'s range [" + min + ", " + max + "]. Refused rather than "
                    + "wrapped; CAST an operand to BIGINT for the 64-bit answer.");
        }
        return value;
    }
}
