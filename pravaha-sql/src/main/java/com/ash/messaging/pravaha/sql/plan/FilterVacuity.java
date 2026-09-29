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
package com.ash.messaging.pravaha.sql.plan;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.runtime.plan.Expression;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.security.SecurityErrors;

/**
 * Whether a row filter restricts anything: the analysis behind ADR-031's refusal of a filter that is
 * true for every row, applied to the predicate the engine will actually run (TAUTOFILTER-1).
 *
 * <p><strong>Why the compiled predicate.</strong> The refusal used to fire only when the planner folded a
 * filter away entirely, which it does for {@code TRUE} and almost nothing else: {@code region = region},
 * {@code 1 = 1 OR region = 'x'} and {@code b IS TRUE OR b IS NOT TRUE} all reach the plan as predicates,
 * and were accepted as if they restricted something. Judging the {@link Predicate} rather than the SQL
 * text judges what is enforced -- the compiler has already pushed every {@code NOT} into the comparisons
 * and settled three-valued logic, so each node answers "keep" or "drop" and nothing else.
 *
 * <p><strong>How.</strong> The predicate becomes a propositional formula over two kinds of variable: "this
 * value is present" (one per column, or per expression that can be null for reasons of its own) and "this
 * comparison holds of present values" (one per distinct comparison, shared with its complement:
 * {@code a < 5} and {@code a >= 5} are one variable and its negation, because on present integers they
 * are). Each comparison is its presence guards AND its value, which is exactly how it evaluates: a
 * comparison with a NULL drops the row. Then:
 *
 * <ul>
 *   <li>a sub-predicate that reads no column is evaluated once and becomes a constant -- {@code 1 = 1}
 *       and {@code 1 = 0};
 *   <li>an expression compared with itself has a known value when present -- {@code =}, {@code <=},
 *       {@code >=} hold and {@code <>}, {@code <}, {@code >} do not -- except over floating point, where
 *       {@code NaN} is present and unequal to itself, so there it stays a variable;
 *   <li>a column declared {@code NOT NULL} is present in every row, and so is anything computed from
 *       present values by a function that is null only when an argument is.
 * </ul>
 *
 * <p>The formula is decided exactly by case-splitting on its variables. Every real row is one assignment
 * of them, so a formula true under every assignment is a filter true for every row -- the analysis is
 * <em>sound</em>: it never calls a restricting filter vacuous. An integer or decimal column compared with
 * constants is split on its value's region rather than comparison by comparison, so {@code a < 5 OR a > 2}
 * and {@code a <= 4 OR a >= 5} are recognised (VACUITYGAP-1; see {@link #regions}). It is still not
 * complete -- floating point, and comparisons between two expressions, keep a variable per comparison;
 * {@code d < 5 OR d >= 5} is not recognised, and would not be a tautology for {@code NaN} anyway -- and a
 * filter too large to decide within a fixed budget is
 * accepted rather than refused; the rule is "refuse what is shown vacuous", never "refuse what cannot be
 * shown to restrict", because refusing a genuine filter would stop a reader who is entitled to rows.
 *
 * <p><strong>The verdicts, and NULL.</strong> {@code region = region} is not true for every row: when
 * {@code region} is NULL it is UNKNOWN and the row is dropped, so on a nullable column it means
 * {@code region IS NOT NULL}. Whether that counts as a restriction is decided here, once:
 *
 * <ul>
 *   <li>{@link Verdict#ALWAYS_TRUE} -- keeps every row the object can carry, NOT NULL columns read as
 *       never null. Refused wherever it is applied ({@code PRV-7003}).
 *   <li>{@link Verdict#NULLS_ONLY} -- taken at face value, with every compared column present, it keeps
 *       every row; the only rows it drops are those where a compared value is NULL, which three-valued
 *       logic drops as a side effect rather than because the filter asked. {@code region = region},
 *       {@code b OR NOT b} and {@code region = 'x' OR region <> 'x'} on a nullable column are each this.
 *       Refused too: a null test written as a comparison is almost always a mistake for a comparison
 *       with a value, and an administrator reading it believes it restricts by value. A filter that says
 *       {@code region IS NOT NULL} in so many words is not this -- an explicit null test is kept as a
 *       variable at face value -- and is accepted.
 *   <li>{@link Verdict#ALWAYS_FALSE} -- keeps no row. Not a leak: it fails closed. Refused when a
 *       catalogue policy that reads nothing about the session is created, because then it keeps no row
 *       for anybody and a deny says that plainly; accepted when bound to a principal, because there it
 *       can be the right answer for that principal ({@code is_member('eu') AND region = 'EU'} for a
 *       non-member) and refusing would turn "you may see none of it" into an error.
 * </ul>
 */
public final class FilterVacuity {

    /** What a filter does to the rows it is applied to. */
    public enum Verdict {
        /** Keeps some rows and drops others by their values; or too large to decide, and so assumed to. */
        RESTRICTS,
        /** Keeps every row the object can carry. */
        ALWAYS_TRUE,
        /** Keeps every row whose compared values are present, and states no null test of its own. */
        NULLS_ONLY,
        /** Keeps no row. */
        ALWAYS_FALSE
    }

    /** Case splits allowed per question before the analysis gives up and assumes the filter restricts. */
    static final int BUDGET = 100_000;

    private static final Formula TRUE = new Const(true);
    private static final Formula FALSE = new Const(false);

    /** A row every read of which throws: what a sub-predicate that reads no column never notices. */
    private static final RowView NO_ROW = (RowView) Proxy.newProxyInstance(
            FilterVacuity.class.getClassLoader(), new Class<?>[] {RowView.class}, (proxy, method, args) -> {
                throw new ReadsTheRow();
            });

    private final StreamSchema schema;
    private final Map<List<Object>, Integer> variables = new HashMap<>();
    private final int limit;
    private int budget;

    private FilterVacuity(StreamSchema schema, int limit) {
        this.schema = schema;
        this.limit = limit;
    }

    /**
     * What {@code predicate} does to rows of {@code schema}.
     *
     * @param predicate the compiled filter, planned directly over {@code schema}'s columns; null when the
     *     planner folded the filter away, which is {@link Verdict#ALWAYS_TRUE}
     */
    public static Verdict of(Predicate predicate, StreamSchema schema) {
        return of(predicate, schema, BUDGET);
    }

    /** {@link #of(Predicate, StreamSchema)} with {@code limit} case splits per question. */
    static Verdict of(Predicate predicate, StreamSchema schema, int limit) {
        if (predicate == null) {
            return Verdict.ALWAYS_TRUE;
        }
        FilterVacuity analysis = new FilterVacuity(schema, limit);
        try {
            Formula exact = analysis.formula(predicate, false);
            if (analysis.always(exact, true)) {
                return Verdict.ALWAYS_TRUE;
            }
            if (analysis.always(exact, false)) {
                return Verdict.ALWAYS_FALSE;
            }
            if (analysis.always(analysis.formula(predicate, true), true)) {
                return Verdict.NULLS_ONLY;
            }
        } catch (Exhausted e) {
            // Too large to decide: assumed to restrict, since refusing a genuine filter stops a reader
            // entitled to rows, and the class-level comment promises only to refuse what is shown.
        }
        return Verdict.RESTRICTS;
    }

    /**
     * Refuses {@code predicate} with {@code PRV-7003} when it restricts nothing -- {@link
     * Verdict#ALWAYS_TRUE} or {@link Verdict#NULLS_ONLY} -- and returns its verdict otherwise.
     *
     * @param subject what the filter is on, for the message: "the row filter on payments (region = region)"
     * @param advice what to do instead, appended to the message
     */
    public static Verdict requireRestricts(Predicate predicate, StreamSchema schema, String subject, String advice) {
        Verdict verdict = of(predicate, schema);
        String why =
                switch (verdict) {
                    case ALWAYS_TRUE ->
                        " is true for every row this object can carry, so it restricts nothing "
                                + "(constants folded, each value compared with itself taken as equal, NOT NULL columns "
                                + "read as never null)";
                    case NULLS_ONLY ->
                        " keeps every row whose values are present: taken at face value its "
                                + "comparisons hold for any value, and the only rows it drops are those where a compared "
                                + "column is NULL -- which SQL drops as a side effect, not because the filter asks. A "
                                + "column compared with itself (region = region) is the usual cause. If dropping rows "
                                + "with a missing value is the point, say so with IS NOT NULL; otherwise compare the "
                                + "column with the value the reader may see";
                    default -> null;
                };
        if (why != null) {
            throw new PravahaException(
                    SecurityErrors.FILTER_NOT_ENFORCEABLE,
                    subject + why + ". Refused rather than served as though it restricted something (TAUTOFILTER-1); "
                            + advice);
        }
        return verdict;
    }

    // ---------------------------------------------------------------------------- the formula

    /**
     * {@code predicate} as a formula. {@code faceValue} reads every column a comparison touches as
     * present; a null test the filter states itself keeps its variable either way.
     */
    private Formula formula(Predicate predicate, boolean faceValue) {
        Boolean constant = constant(predicate);
        if (constant != null) {
            return constant ? TRUE : FALSE;
        }
        return switch (predicate) {
            case Predicate.True t -> TRUE;
            case Predicate.False f -> FALSE;
            case Predicate.And and -> and(formulas(and.parts(), faceValue));
            case Predicate.Or or -> or(formulas(or.parts(), faceValue));
            case Predicate.Not not -> not(formula(not.inner(), faceValue));
            case Predicate.IsNull test -> {
                Formula present = columnPresent(test.ordinal(), false);
                yield test.wantNull() ? not(present) : present;
            }
            case Predicate.IsNullExpression test -> {
                Formula present = present(test.value(), false);
                yield test.wantNull() ? not(present) : present;
            }
            case Predicate.CompareBoolean compare ->
                and(List.of(
                        columnPresent(compare.ordinal(), faceValue),
                        literal(List.of("boolean", compare.ordinal()), compare.value())));
            case Predicate.CompareString compare ->
                and(List.of(
                        columnPresent(compare.ordinal(), faceValue),
                        literal(List.of("text", compare.ordinal(), compare.value()), compare.op() == Predicate.Op.EQ)));
            case Predicate.CompareLong compare ->
                and(List.of(
                        columnPresent(compare.ordinal(), faceValue),
                        ordered(List.of("integer", compare.ordinal(), compare.value()), compare.op())));
            case Predicate.CompareInt compare ->
                and(List.of(
                        columnPresent(compare.ordinal(), faceValue),
                        ordered(List.of("integer", compare.ordinal(), (long) compare.value()), compare.op())));
            case Predicate.CompareDouble compare ->
                and(List.of(
                        columnPresent(compare.ordinal(), faceValue),
                        floating(List.of("floating", compare.ordinal(), compare.value()), compare.op())));
            case Predicate.CompareDecimal compare ->
                and(List.of(
                        columnPresent(compare.ordinal(), faceValue),
                        ordered(List.of("decimal", compare.ordinal(), compare.high(), compare.low()), compare.op())));
            case Predicate.Like like ->
                and(List.of(
                        columnPresent(like.ordinal(), faceValue),
                        matchesAnything(like.pattern())
                                ? (like.negated() ? FALSE : TRUE)
                                : literal(List.of("like", like.ordinal(), like.pattern()), !like.negated())));
            case Predicate.CompareExpressions compare -> compared(compare, faceValue);
        };
    }

    private List<Formula> formulas(List<Predicate> parts, boolean faceValue) {
        List<Formula> out = new ArrayList<>(parts.size());
        for (Predicate part : parts) {
            out.add(formula(part, faceValue));
        }
        return out;
    }

    /**
     * Two expressions compared, as {@link Predicate.CompareExpressions#test} evaluates them: dropped when
     * either is null; text by equality; decimals exactly; floating point as IEEE 754; the rest as integers.
     */
    private Formula compared(Predicate.CompareExpressions compare, boolean faceValue) {
        Formula againstConstant = decimalColumnAgainstConstant(compare, faceValue);
        if (againstConstant != null) {
            return againstConstant;
        }
        Expression left = compare.left();
        Expression right = compare.right();
        Predicate.Op op = compare.op();
        Formula guard = and(List.of(present(left, faceValue), present(right, faceValue)));
        boolean text = left.type() == TypeName.STRING;
        boolean decimal = left.type() == TypeName.DECIMAL || right.type() == TypeName.DECIMAL;
        boolean floating = !text && !decimal && (left.isFloatingPoint() || right.isFloatingPoint());
        Formula value;
        if (left.equals(right)) {
            // An expression over the row is a function of the row: evaluated twice, it answers the same.
            if (text) {
                value = op == Predicate.Op.EQ ? TRUE : FALSE;
            } else if (floating) {
                value = switch (op) {
                    case LT, GT -> FALSE;
                    case EQ, LE, GE -> variable(List.of("not NaN", left));
                    case NE -> not(variable(List.of("not NaN", left)));
                };
            } else {
                value = op.matches(0) ? TRUE : FALSE;
            }
        } else if (text) {
            value = literal(List.of("compare", left, Predicate.Op.EQ, right), op == Predicate.Op.EQ);
        } else if (floating) {
            value = floating(List.of("compare", left, right), op);
        } else {
            value = ordered(List.of("compare", left, right), op);
        }
        return and(List.of(guard, value));
    }

    /**
     * A DECIMAL column compared with a constant through an expression comparison, as the same
     * whole-number comparison {@link Predicate.CompareDecimal} makes, so that it shares its variables
     * and its regions (DECSCALE-1); null when the comparison is not that, and it is judged as before.
     *
     * <p>The planner writes {@code p >= 5} over a {@code DECIMAL(10, 2)} as the column rescaled to a
     * wider type against the literal, which evaluates to exactly the column's value -- when the
     * rescale cannot fail. So the column's own value {@code u / 10^s} ({@code u} its unscaled whole
     * number) is compared with the constant {@code c}: {@code u op c * 10^s}. When {@code c * 10^s}
     * is a whole number that is the comparison; when it is not, no {@code u} equals it, and {@code u
     * < x} and {@code u <= x} are both {@code u < ceil(x)} -- {@code p >= 4.995} at scale 2 is {@code
     * p >= 5.00}. Anything else -- a rescale that could throw (narrowing, or fewer integer digits than
     * the column holds), a side that is not a constant, a boundary past 128 bits -- falls back.
     *
     * <p>"Cannot fail" is judged against the column's declared precision. A stored value wider than
     * that makes the rescale throw at run time, and the row goes to the dead-letter queue rather than
     * through the filter -- so it is not a row the filter keeps or drops by value, and no verdict here
     * admits it.
     */
    private Formula decimalColumnAgainstConstant(Predicate.CompareExpressions compare, boolean faceValue) {
        Predicate.Op op = compare.op();
        Expression columnSide = compare.left();
        java.math.BigDecimal constant = constantDecimal(compare.right());
        if (constant == null) {
            columnSide = compare.right();
            constant = constantDecimal(compare.left());
            op = mirrored(op);
        }
        Expression.DecimalColumn column = exactDecimalColumn(columnSide);
        if (constant == null || column == null) {
            return null;
        }
        java.math.BigDecimal scaled = constant.movePointRight(column.scale());
        boolean whole = scaled.signum() == 0 || scaled.stripTrailingZeros().scale() <= 0;
        BigInteger boundary = whole
                ? scaled.toBigIntegerExact()
                : scaled.setScale(0, java.math.RoundingMode.CEILING).toBigIntegerExact();
        if (boundary.bitLength() > 127) {
            return null;
        }
        List<Object> key =
                List.of("decimal", column.ordinal(), boundary.shiftRight(64).longValue(), boundary.longValue());
        Formula value;
        if (whole) {
            value = ordered(key, op);
        } else {
            value = switch (op) {
                case EQ -> FALSE;
                case NE -> TRUE;
                case LT, LE -> ordered(key, Predicate.Op.LT);
                case GT, GE -> ordered(key, Predicate.Op.GE);
            };
        }
        return and(List.of(columnPresent(column.ordinal(), faceValue), value));
    }

    /**
     * The value of {@code expression} when it reads no row, is not null and is a decimal or an
     * integer; otherwise null.
     */
    private static java.math.BigDecimal constantDecimal(Expression expression) {
        if (expression.type() != TypeName.DECIMAL
                && (expression.type() == TypeName.STRING || expression.isFloatingPoint())) {
            return null;
        }
        try {
            return expression.isNull(NO_ROW) ? null : Expression.decimalOf(expression, NO_ROW);
        } catch (RuntimeException readsTheRowOrFails) {
            return null;
        }
    }

    /**
     * The DECIMAL column whose exact value {@code expression} is: the column itself, or the column
     * rescaled to a type that holds every value it can -- no fewer fraction digits, no fewer integer
     * digits -- which is the only rescale that can neither round nor throw. Null for anything else.
     */
    private Expression.DecimalColumn exactDecimalColumn(Expression expression) {
        if (expression instanceof Expression.DecimalColumn column) {
            return column;
        }
        if (expression instanceof Expression.DecimalRescale rescale
                && rescale.source() instanceof Expression.DecimalColumn column
                && column.ordinal() >= 0
                && column.ordinal() < schema.fieldCount()
                && schema.field(column.ordinal()).type()
                        instanceof com.ash.messaging.pravaha.api.data.DecimalType declared
                && declared.scale() == column.scale()
                && rescale.scale() >= column.scale()
                && rescale.precision() - rescale.scale() >= declared.precision() - declared.scale()) {
            return column;
        }
        return null;
    }

    /** {@code c op x} as {@code x op' c}. */
    private static Predicate.Op mirrored(Predicate.Op op) {
        return switch (op) {
            case LT -> Predicate.Op.GT;
            case LE -> Predicate.Op.GE;
            case GT -> Predicate.Op.LT;
            case GE -> Predicate.Op.LE;
            case EQ, NE -> op;
        };
    }

    /** A total order's comparison: {@code op} and its negation are one variable and its complement. */
    private Formula ordered(List<Object> key, Predicate.Op op) {
        Predicate.Op base =
                switch (op) {
                    case EQ, LT, LE -> op;
                    case NE, GE, GT -> op.negated();
                };
        Formula literal = literal(append(key, base), base == op);
        bound(key, base);
        return literal;
    }

    // ---------------------------------------------------------------------------- one column's values

    /**
     * A variable that stands for one column compared with a constant: {@code column} is {@code
     * ("integer", ordinal)} or {@code ("decimal", ordinal)}, {@code op} one of {@code =}, {@code <},
     * {@code <=} (the others are these negated).
     */
    private record Bound(List<Object> column, BigInteger constant, Predicate.Op op) {}

    private final Map<Integer, Bound> bounds = new HashMap<>();

    private final Map<List<Object>, List<Integer>> boundsByColumn = new HashMap<>();

    /** {@link #regions} per column, worked out once per question rather than once per split. */
    private final Map<List<Object>, List<Map<Integer, Boolean>>> regionsOf = new HashMap<>();

    /**
     * Records that the variable for {@code key}+{@code op} compares a column with a constant, so that
     * {@link #decide} splits on the column's value rather than on each comparison alone (VACUITYGAP-1).
     *
     * <p>Only integers and decimals: both compare as whole numbers -- a decimal's unscaled value against
     * the constant's, at the column's scale -- so the values between two constants are known exactly.
     * A comparison of two expressions has no constant, and floating point has {@code NaN}, unordered
     * against everything; both keep their independent variables, which is sound and merely less complete.
     */
    private void bound(List<Object> key, Predicate.Op op) {
        Object kind = key.get(0);
        BigInteger constant;
        if ("integer".equals(kind)) {
            constant = BigInteger.valueOf((Long) key.get(2));
        } else if ("decimal".equals(kind)) {
            constant = BigInteger.valueOf((Long) key.get(2))
                    .shiftLeft(64)
                    .add(new BigInteger(Long.toUnsignedString((Long) key.get(3))));
        } else {
            return;
        }
        int id = variables.get(append(key, op));
        if (bounds.containsKey(id)) {
            return;
        }
        List<Object> column = List.of(kind, key.get(1));
        bounds.put(id, new Bound(column, constant, op));
        boundsByColumn.computeIfAbsent(column, k -> new ArrayList<>()).add(id);
        regionsOf.remove(column);
    }

    /** The least and greatest value a column of this kind can hold, as {@link #bound} reads it. */
    private static BigInteger[] domain(List<Object> column) {
        return "integer".equals(column.get(0))
                ? new BigInteger[] {BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE)}
                : new BigInteger[] {
                    BigInteger.ONE.shiftLeft(127).negate(),
                    BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE)
                };
    }

    /**
     * Every consistent truth assignment of one column's comparisons with constants: one per region of
     * the number line the constants cut it into -- each constant itself, and each run of whole numbers
     * strictly between two neighbouring constants (or beyond the outermost) that has a number in it.
     * Every present value falls in exactly one region, and every comparison answers the same across a
     * region, so these are exactly the assignments some real value makes: {@code a < 5 OR a >= 5} is
     * then true under all of them, where as independent variables it was not. Regions with no number in
     * them ({@code 4 < a < 5}) are left out, which is what makes {@code a <= 4 OR a >= 5} a tautology;
     * leaving out a region some value lies in would be unsound, so a region is kept unless provably empty.
     */
    private List<Map<Integer, Boolean>> regions(List<Object> column) {
        List<Integer> ids = boundsByColumn.get(column);
        java.util.TreeSet<BigInteger> constants = new java.util.TreeSet<>();
        for (int id : ids) {
            constants.add(bounds.get(id).constant());
        }
        BigInteger[] domain = domain(column);
        List<Map<Integer, Boolean>> out = new ArrayList<>();
        BigInteger previous = null;
        for (BigInteger constant : constants) {
            // The open run below this constant: from the previous constant (or the domain's floor) up.
            BigInteger low = previous == null ? domain[0].subtract(BigInteger.ONE) : previous;
            if (constant.subtract(low).compareTo(BigInteger.ONE) > 0) {
                out.add(assignment(ids, low.add(BigInteger.ONE)));
            }
            out.add(assignment(ids, constant));
            previous = constant;
        }
        if (previous.compareTo(domain[1]) < 0) {
            out.add(assignment(ids, previous.add(BigInteger.ONE)));
        }
        return out;
    }

    /** Each comparison's answer for {@code value}, which stands for its whole region. */
    private Map<Integer, Boolean> assignment(List<Integer> ids, BigInteger value) {
        Map<Integer, Boolean> out = new HashMap<>();
        for (int id : ids) {
            Bound bound = bounds.get(id);
            out.put(id, bound.op().matches(value.compareTo(bound.constant())));
        }
        return out;
    }

    /**
     * IEEE 754: {@code =} and {@code <>} are still complements ({@code NaN <> x} holds), the orderings are
     * not ({@code NaN < x} and {@code NaN >= x} both fail), so each ordering is a variable of its own.
     */
    private Formula floating(List<Object> key, Predicate.Op op) {
        return switch (op) {
            case EQ -> literal(append(key, Predicate.Op.EQ), true);
            case NE -> literal(append(key, Predicate.Op.EQ), false);
            default -> literal(append(key, op), true);
        };
    }

    /** Whether {@code expression} is non-null, as a formula. */
    private Formula present(Expression expression, boolean faceValue) {
        try {
            return expression.isNull(NO_ROW) ? FALSE : TRUE;
        } catch (RuntimeException readsTheRow) {
            // below
        }
        return switch (expression) {
            case Expression.Column column -> columnPresent(column.ordinal(), faceValue);
            case Expression.DecimalColumn column -> columnPresent(column.ordinal(), faceValue);
            // Each of these is null exactly when one of its arguments is (their isNull says so).
            case Expression.Cast cast -> present(cast.source(), faceValue);
            case Expression.TextFunction function -> present(function.argument(), faceValue);
            case Expression.Unary unary -> present(unary.argument(), faceValue);
            case Expression.DateFormat format -> present(format.source(), faceValue);
            case Expression.DecimalRescale rescale -> present(rescale.source(), faceValue);
            case Expression.DecimalToDouble widen -> present(widen.source(), faceValue);
            case Expression.Arithmetic arithmetic ->
                and(List.of(present(arithmetic.left(), faceValue), present(arithmetic.right(), faceValue)));
            case Expression.DecimalArithmetic arithmetic ->
                and(List.of(present(arithmetic.left(), faceValue), present(arithmetic.right(), faceValue)));
            case Expression.Concat concat -> {
                List<Formula> parts = new ArrayList<>();
                for (Expression part : concat.parts()) {
                    parts.add(present(part, faceValue));
                }
                yield and(parts);
            }
            case Expression.Substring substring -> {
                List<Formula> parts = new ArrayList<>();
                parts.add(present(substring.source(), faceValue));
                parts.add(present(substring.start(), faceValue));
                if (substring.length() != null) {
                    parts.add(present(substring.length(), faceValue));
                }
                yield and(parts);
            }
            // CASE, REGEXP_EXTRACT and SPLIT_INDEX can be null from present arguments: a variable of their own.
            default -> variable(List.of("present", expression));
        };
    }

    /** Whether column {@code ordinal} is non-null: always, for a NOT NULL column or read at face value. */
    private Formula columnPresent(int ordinal, boolean faceValue) {
        boolean nullable = ordinal < 0
                || ordinal >= schema.fieldCount()
                || schema.field(ordinal).type().nullable();
        return !nullable || faceValue ? TRUE : variable(List.of("column present", ordinal));
    }

    /** {@code LIKE} with a pattern of nothing but {@code %}: every present string matches. */
    private static boolean matchesAnything(String pattern) {
        return !pattern.isEmpty() && pattern.chars().allMatch(c -> c == '%');
    }

    /** What {@code predicate} answers without reading the row, or null when it reads it. */
    private static Boolean constant(Predicate predicate) {
        try {
            return predicate.test(NO_ROW);
        } catch (RuntimeException readsTheRowOrFails) {
            // A read of the row, or a failure evaluating it: either way not a constant to fold.
            return null;
        }
    }

    private static List<Object> append(List<Object> key, Object more) {
        List<Object> out = new ArrayList<>(key);
        out.add(more);
        return List.copyOf(out);
    }

    private Formula literal(List<Object> key, boolean positive) {
        Formula variable = variable(key);
        return positive ? variable : not(variable);
    }

    private Formula variable(List<Object> key) {
        return new Var(variables.computeIfAbsent(key, k -> variables.size()));
    }

    // ---------------------------------------------------------------------------- deciding

    /** Whether {@code formula} is {@code target} under every assignment of its variables. */
    private boolean always(Formula formula, boolean target) {
        budget = limit;
        return decide(formula, target);
    }

    private boolean decide(Formula formula, boolean target) {
        if (formula instanceof Const c) {
            return c.value() == target;
        }
        if (--budget < 0) {
            throw new Exhausted();
        }
        int split = firstVariable(formula);
        Bound bound = bounds.get(split);
        if (bound != null) {
            // A column compared with constants: split on its value's region, all its comparisons at once.
            for (Map<Integer, Boolean> region : regionsOf.computeIfAbsent(bound.column(), this::regions)) {
                Formula assigned = formula;
                for (Map.Entry<Integer, Boolean> each : region.entrySet()) {
                    assigned = assign(assigned, each.getKey(), each.getValue());
                }
                if (!decide(assigned, target)) {
                    return false;
                }
            }
            return true;
        }
        return decide(assign(formula, split, true), target) && decide(assign(formula, split, false), target);
    }

    private static int firstVariable(Formula formula) {
        return switch (formula) {
            case Var v -> v.id();
            case Neg n -> firstVariable(n.inner());
            case All all -> firstVariable(all.parts().get(0));
            case Any any -> firstVariable(any.parts().get(0));
            case Const c -> throw new IllegalStateException("a constant has no variable");
        };
    }

    private static Formula assign(Formula formula, int id, boolean value) {
        return switch (formula) {
            case Const c -> c;
            case Var v -> v.id() == id ? (value ? TRUE : FALSE) : v;
            case Neg n -> not(assign(n.inner(), id, value));
            case All all ->
                and(all.parts().stream().map(p -> assign(p, id, value)).toList());
            case Any any ->
                or(any.parts().stream().map(p -> assign(p, id, value)).toList());
        };
    }

    private static Formula not(Formula formula) {
        return switch (formula) {
            case Const c -> c.value() ? FALSE : TRUE;
            case Neg n -> n.inner();
            default -> new Neg(formula);
        };
    }

    private static Formula and(List<Formula> parts) {
        List<Formula> kept = new ArrayList<>(parts.size());
        for (Formula part : parts) {
            if (part instanceof Const c) {
                if (!c.value()) {
                    return FALSE;
                }
            } else if (part instanceof All all) {
                kept.addAll(all.parts());
            } else {
                kept.add(part);
            }
        }
        return kept.isEmpty() ? TRUE : kept.size() == 1 ? kept.get(0) : new All(List.copyOf(kept));
    }

    private static Formula or(List<Formula> parts) {
        List<Formula> kept = new ArrayList<>(parts.size());
        for (Formula part : parts) {
            if (part instanceof Const c) {
                if (c.value()) {
                    return TRUE;
                }
            } else if (part instanceof Any any) {
                kept.addAll(any.parts());
            } else {
                kept.add(part);
            }
        }
        return kept.isEmpty() ? FALSE : kept.size() == 1 ? kept.get(0) : new Any(List.copyOf(kept));
    }

    private sealed interface Formula permits Const, Var, Neg, All, Any {}

    private record Const(boolean value) implements Formula {}

    private record Var(int id) implements Formula {}

    private record Neg(Formula inner) implements Formula {}

    private record All(List<Formula> parts) implements Formula {}

    private record Any(List<Formula> parts) implements Formula {}

    /** Thrown by {@link #NO_ROW}: the predicate reads the row, so it is not a constant. */
    private static final class ReadsTheRow extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReadsTheRow() {
            super(null, null, false, false);
        }
    }

    /** The case-split budget ran out. */
    private static final class Exhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Exhausted() {
            super(null, null, false, false);
        }
    }
}
