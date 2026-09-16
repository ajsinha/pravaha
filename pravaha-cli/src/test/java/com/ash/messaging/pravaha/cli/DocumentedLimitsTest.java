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
package com.ash.messaging.pravaha.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * X-11: two real ceilings that used to report no {@code PRV-} code and no documented shape, pinned
 * to what they now actually do and to the paragraphs in {@code TROUBLESHOOTING.md} that describe
 * them.
 *
 * <p>{@code BinaryRowWriter} (in {@code pravaha-common}) now refuses a 65th field as a
 * {@link com.ash.messaging.pravaha.api.PravahaException} carrying {@code PRV-3030}, and a predicate
 * too large for the planner to convert is now summarised -- the operator and the term count, not the
 * whole text -- and carries its own {@code PRV-2011} rather than sharing the planner's generic
 * catch-all. A limit nobody wrote down is one every user discovers the expensive way, and a
 * written-down limit rots the moment the number behind it moves, so this measures the real behaviour
 * and then asserts the document says the same thing, rather than quoting either one from memory.
 */
@Timeout(300)
class DocumentedLimitsTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return new PravahaCli(
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(args);
    }

    @Test
    void aRowIsCappedAtSixtyFourColumnsAndTheDocumentSaysSo() {
        // Measured rather than asserted from the constant, because the constant is Long.SIZE and the
        // interesting claim is what a user meets: 64 works, 65 does not.
        assertThat(writerFor(64)).isNotNull();

        assertThatThrownBy(() -> writerFor(65))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("at most 64 fields")
                // The finding's own point, recorded as an assertion rather than as prose: a clear
                // diagnosis that now carries a stable, documented code.
                .hasMessageContaining("PRV-3030");

        String troubleshooting = troubleshooting();
        assertThat(troubleshooting)
                .as("the 64-column ceiling must be written down where a user searching for it looks")
                .contains("BinaryRowWriter")
                .contains("64 columns")
                .contains("at most 64 fields")
                .contains("PRV-3030");

        String continuousQueries = continuousQueries();
        assertThat(continuousQueries)
                .as("a reader writing a wide query should find the ceiling in CONTINUOUS_QUERIES.md too")
                .contains("64")
                .contains("PRV-3030");
    }

    @Test
    void aVeryLongBooleanChainIsSummarisedRatherThanEchoedAndTheDocumentSaysSo() {
        // 3,000 conjuncts: the shape a generated query or an IN-list rewrite produces. 1,000 plans
        // cleanly on the current tree, so the figure in the register is no longer the threshold --
        // which is exactly why this measures rather than quotes it.
        StringBuilder sql = new StringBuilder("SELECT amount FROM txn WHERE ");
        for (int i = 0; i < 3_000; i++) {
            sql.append(i == 0 ? "" : " AND ").append("amount > ").append(i);
        }
        assertThat(run("validate", "--sql", sql.toString(), "--schema", "txn_id:INT64,amount:INT64"))
                .isEqualTo(1);

        String stderr = err.toString(StandardCharsets.UTF_8);
        assertThat(stderr)
                .as("a predicate too large to compile is its own code, not the planner's catch-all")
                .contains("PRV-2011")
                // The finding's own point: the message says how large the predicate was rather than
                // reproducing it. "3000" is the term count this specific chain has.
                .contains("3000-term")
                .contains("AND chain");
        // The defect this fixes: the refusal used to be longer than the query. Pinned as a ceiling
        // rather than an exact size -- a regression that goes back to dumping the whole predicate
        // fails here well before it reaches anything like the old ~125,000-character message.
        assertThat(stderr.length())
                .as("the whole predicate must not be interpolated verbatim: %d characters of stderr", stderr.length())
                .isLessThan(2_000);
        assertThat(stderr).doesNotContain("CAST(2999 AS BIGINT)");

        assertThat(troubleshooting())
                .as("the predicate-too-large refusal must be written down alongside the other codes")
                .contains("PRV-2011")
                .contains("term");
    }

    private static BinaryRowWriter writerFor(int columns) {
        StreamSchema.Builder schema = StreamSchema.builder("wide");
        for (int i = 0; i < columns; i++) {
            schema.field("c" + i, Types.int64());
        }
        return new BinaryRowWriter(RowLayout.of(schema.build()));
    }

    private static String troubleshooting() {
        try {
            return Files.readString(repoRoot().resolve("docs/TROUBLESHOOTING.md"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read docs/TROUBLESHOOTING.md", e);
        }
    }

    private static String continuousQueries() {
        try {
            return Files.readString(repoRoot().resolve("docs/CONTINUOUS_QUERIES.md"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read docs/CONTINUOUS_QUERIES.md", e);
        }
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("docs/adr"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IllegalStateException(
                    "could not find the repository root from " + Path.of("").toAbsolutePath());
        }
        return path;
    }
}
