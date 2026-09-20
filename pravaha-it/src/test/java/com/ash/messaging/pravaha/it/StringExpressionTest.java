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
package com.ash.messaging.pravaha.it;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Text in a computed column.
 *
 * <p>Until now the expression tree evaluated to a number and nothing else, so text could be carried
 * through a projection but never computed with. That refused a tier of ordinary SQL -- {@code
 * UPPER(region)}, {@code first || ' ' || last}, {@code SUBSTRING(code FROM 1 FOR 3)} -- for a reason
 * internal to the engine rather than anything about the query.
 *
 * <p>The tests worth reading are the ones where the obvious implementation is wrong: null
 * concatenation, a substring that starts before the string does, a substring counted in code points
 * rather than {@code char}s, and case conversion that must not depend on the machine's locale.
 */
class StringExpressionTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("person")
            .field("id", Types.int64())
            .field("first", Types.string())
            .field("last", Types.string().withNullable(true))
            .field("amount", Types.int64())
            .build();

    @Test
    void upperAndLowerConvertCase() {
        assertThat(text("SELECT UPPER(first) FROM person", "ann", "lee")).isEqualTo("ANN");
        assertThat(text("SELECT LOWER(first) FROM person", "ANN", "lee")).isEqualTo("ann");
    }

    @Test
    void caseConversionDoesNotDependOnTheMachinesLocale() {
        // The bug this guards is real and invisible on an English laptop. String.toUpperCase() with
        // no argument uses the default locale, and in Turkish a dotless capital I is a different
        // letter -- so UPPER('id') is 'İD' there and 'ID' everywhere else. A query that groups by
        // UPPER(code) would then produce different groups on different machines in the same cluster,
        // which is the worst kind of wrong: it reconciles on one node and not on another.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(text("SELECT UPPER(first) FROM person", "id", "x")).isEqualTo("ID");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void trimStripsSpacesFromBothEnds() {
        assertThat(text("SELECT TRIM(first) FROM person", "  ann  ", "lee")).isEqualTo("ann");
    }

    @Test
    void trimStripsSpacesAndOnlySpaces() {
        // SQL's default trim character is a space, not "whitespace". Java's strip() would take the
        // tab too and look more helpful right up until somebody depends on it.
        assertThat(text("SELECT TRIM(first) FROM person", " \tann\t ", "lee")).isEqualTo("\tann\t");
    }

    @Test
    void concatenationJoinsInOrder() {
        assertThat(text("SELECT first || '-' || last FROM person", "ann", "lee"))
                .isEqualTo("ann-lee");
    }

    @Test
    void nullConcatenatedWithAnythingIsNull() {
        // SQL's rule, and the reason `first || ' ' || last` on a row with no surname gives null
        // rather than a name with a trailing space. Treating null as an empty string here would
        // produce output that looks right and quietly loses the distinction.
        List<CapturingRowWriter.Captured> out = run("SELECT first || ' ' || last FROM person", "ann", null);
        assertThat(out.get(0).isNull(0)).isTrue();
    }

    @Test
    void substringTakesALengthFromAOneBasedStart() {
        assertThat(text("SELECT SUBSTRING(first FROM 2 FOR 3) FROM person", "abcdef", "x"))
                .isEqualTo("bcd");
    }

    @Test
    void substringWithoutALengthRunsToTheEnd() {
        assertThat(text("SELECT SUBSTRING(first FROM 3) FROM person", "abcdef", "x"))
                .isEqualTo("cdef");
    }

    @Test
    void aStartBeforeTheStringContributesNothingRatherThanShiftingTheWindow() {
        // The standard defines the result as the positions between start and start + length that
        // actually exist. Positions -1 and 0 are not in 'abcde', so FROM -1 FOR 4 yields two
        // characters. Clamping start to 1 -- the fix that looks obviously right -- returns four and
        // disagrees with every other database.
        assertThat(text("SELECT SUBSTRING(first FROM -1 FOR 4) FROM person", "abcde", "x"))
                .isEqualTo("ab");
    }

    @Test
    void aLengthPastTheEndStopsAtTheEnd() {
        assertThat(text("SELECT SUBSTRING(first FROM 2 FOR 999) FROM person", "abc", "x"))
                .isEqualTo("bc");
    }

    @Test
    void aStartPastTheEndIsEmptyRatherThanAnError() {
        assertThat(text("SELECT SUBSTRING(first FROM 9) FROM person", "abc", "x"))
                .isEqualTo("");
    }

    @Test
    void substringCountsCodePointsRatherThanChars() {
        // An emoji outside the basic plane is two Java chars and one character. Indexing by char
        // returns half a surrogate pair, which is not text at all -- it renders as a replacement
        // box and compares equal to nothing. The whole cost of getting this right is an
        // offsetByCodePoints instead of an int.
        assertThat(text("SELECT SUBSTRING(first FROM 1 FOR 2) FROM person", "🚀🚀ab", "x"))
                .isEqualTo("🚀🚀");
    }

    @Test
    void aCaseCanChooseBetweenTwoStrings() {
        assertThat(text("SELECT CASE WHEN amount > 5 THEN 'big' ELSE 'small' END FROM person", "ann", "lee"))
                .isEqualTo("big");
    }

    @Test
    void textFunctionsNest() {
        assertThat(text("SELECT UPPER(TRIM(first)) || '!' FROM person", "  ann  ", "lee"))
                .isEqualTo("ANN!");
    }

    @Test
    void aTrimPravahaCannotDoIsRefusedRatherThanIgnored() {
        // Quietly doing the default trim would return the input unchanged for any value with no
        // leading spaces, which looks exactly like it worked.
        assertThatThrownBy(() -> plan("SELECT TRIM(LEADING ' ' FROM first) FROM person"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("LEADING");
        assertThatThrownBy(() -> plan("SELECT TRIM('x' FROM first) FROM person"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021");
    }

    @Test
    void aStringFunctionThatIsNotBuiltSaysWhatIs() {
        assertThatThrownBy(() -> plan("SELECT REPLACE(first, 'a', 'b') FROM person"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("SUBSTRING");
    }

    @Test
    void theExpressionIsReadableInThePlan() {
        // EXPLAIN has to be matchable against the query that produced it, which means a string
        // literal prints quoted -- otherwise `first || '-'` reads as a column nobody can find.
        assertThat(PhysicalPlanBuilder.explain(plan("SELECT UPPER(first) || '-' FROM person")))
                .contains("UPPER(first) || '-'");
    }

    @Test
    void likeMatchesWildcards() {
        assertThat(matches("WHERE first LIKE 'an%'", "annabel")).isTrue();
        assertThat(matches("WHERE first LIKE 'an%'", "brian")).isFalse();
        assertThat(matches("WHERE first LIKE '%an%'", "brian")).isTrue();
        assertThat(matches("WHERE first LIKE 'a__'", "ann")).isTrue();
        assertThat(matches("WHERE first LIKE 'a__'", "anna")).isFalse();
    }

    @Test
    void likeMatchesTheWholeValueRatherThanPartOfIt() {
        // SQL LIKE is anchored at both ends; a regex find() is not. Getting this wrong makes
        // `LIKE 'ann'` match 'annabel', which is a filter that silently returns too many rows.
        assertThat(matches("WHERE first LIKE 'ann'", "annabel")).isFalse();
    }

    @Test
    void aRegexMetacharacterInThePatternIsALiteral() {
        // The pattern is translated to a regex, so a user writing LIKE '%.com' would get a dot that
        // matches any character if the literal parts were not quoted -- and '%xcom' would match.
        assertThat(matches("WHERE first LIKE '%.com'", "mail.com")).isTrue();
        assertThat(matches("WHERE first LIKE '%.com'", "mailxcom")).isFalse();
    }

    /**
     * Y-6. {@code LIKE}'s {@code _} and {@code SUBSTRING}'s length have to count the same thing, or
     * two clauses of one query disagree about how long a string is.
     *
     * <p>The finding predicted they would: {@code SUBSTRING} was made code-point-correct and
     * {@code LIKE} was left on a regex {@code .}, which the finding read as counting UTF-16 units.
     * It does not -- Java's {@code Pattern} advances by code point, so {@code .} consumes a whole
     * surrogate pair -- and the two agree. Pinned rather than merely recorded, because the
     * translation in {@code Predicate.Like} walks the <em>pattern</em> by {@code char}, and a
     * future edit that walked the <em>subject</em> the same way would break this and nothing else.
     */
    @Test
    void y6_likeAndSubstringAgreeAboutTheLengthOfAStringWithASurrogatePair() {
        assertThat(matches("WHERE first LIKE '_ok'", "👍ok"))
                .as("one _ matches the whole emoji, as one character")
                .isTrue();
        assertThat(matches("WHERE first LIKE '__ok'", "👍ok"))
                .as("and two do not, because there is only one character before 'ok'")
                .isFalse();
        assertThat(text("SELECT SUBSTRING(first FROM 1 FOR 1) FROM person", "👍ok", "x"))
                .as("SUBSTRING counts the same character the same way")
                .isEqualTo("👍");
        assertThat(text("SELECT SUBSTRING(first FROM 2) FROM person", "👍ok", "x"))
                .isEqualTo("ok");
    }

    @Test
    void aNullIsDroppedByLikeAndByNotLikeAlike() {
        // LIKE over a null is UNKNOWN, and UNKNOWN drops the row under both forms. Implementing NOT
        // LIKE as a Java ! around the result keeps the null row instead, which is how a query that
        // looks like a complement ends up returning rows its opposite also returned.
        assertThat(matchesOnLast("WHERE last LIKE 'a%'", null)).isFalse();
        assertThat(matchesOnLast("WHERE last NOT LIKE 'a%'", null)).isFalse();
        assertThat(matchesOnLast("WHERE last NOT LIKE 'a%'", "bob")).isTrue();
    }

    @Test
    void twoRegistrationsOfTheSameLikeAreEqual() {
        // The compiled Pattern cannot be a component: it inherits identity equality, and two
        // registrations of one query would then be given separate computations -- exactly the
        // sharing this engine exists to do.
        assertThat(new com.ash.messaging.pravaha.runtime.plan.Predicate.Like(1, "first", "a%", false))
                .isEqualTo(new com.ash.messaging.pravaha.runtime.plan.Predicate.Like(1, "first", "a%", false))
                .hasSameHashCodeAs(new com.ash.messaging.pravaha.runtime.plan.Predicate.Like(1, "first", "a%", false))
                .isNotEqualTo(new com.ash.messaging.pravaha.runtime.plan.Predicate.Like(1, "first", "b%", false))
                .isNotEqualTo(new com.ash.messaging.pravaha.runtime.plan.Predicate.Like(1, "first", "a%", true));
    }

    @Test
    void likeIsReadableInThePlan() {
        assertThat(PhysicalPlanBuilder.explain(plan("SELECT id FROM person WHERE first NOT LIKE 'a%'")))
                .contains("first NOT LIKE 'a%'");
    }

    /** Whether one row with this {@code first} survives the clause. */
    private static boolean matches(String whereClause, String first) {
        return !run("SELECT id FROM person " + whereClause, first, "lee").isEmpty();
    }

    private static boolean matchesOnLast(String whereClause, String last) {
        return !run("SELECT id FROM person " + whereClause, "ann", last).isEmpty();
    }

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(SCHEMA).plan(sql));
    }

    /** Runs one row and returns the first output column as text. */
    private static String text(String sql, String first, String last) {
        return (String) run(sql, first, last).get(0).values()[0];
    }

    private static List<CapturingRowWriter.Captured> run(String sql, String first, String last) {
        PhysicalOperator plan = plan(sql);
        List<CapturingRowWriter.Captured> results = new ArrayList<>();
        RowLayout layout = RowLayout.of(SCHEMA);
        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 4);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            long handle = feed.allocate(layout.rowSize(256));
            writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
            writer.setLong(0, 1).setString(1, first);
            if (last == null) {
                writer.setNull(2);
            } else {
                writer.setString(2, last);
            }
            writer.setLong(3, 10);
            writer.weight(1L).eventTimestampNanos(1).sequence(1).commit();
            feed.trimTo(handle, writer.sizeSoFar());
            pipeline.accept(new BinaryRowView(layout).wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            pipeline.finish();
        }
        return results;
    }
}
