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

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * X-11: two real ceilings that report no {@code PRV-} code, pinned to what they actually do and to
 * the paragraph in {@code TROUBLESHOOTING.md} that describes them.
 *
 * <p>Both refusals live in modules this round does not own -- {@code BinaryRowWriter} is in
 * {@code pravaha-common} and the conversion failure comes out of Calcite inside {@code pravaha-sql}
 * -- so giving either one a code is recorded as a blocker rather than attempted from here. What
 * <em>is</em> in reach is the half the finding names first: "no documented shape". A limit nobody
 * wrote down is one every user discovers the expensive way, and a written-down limit rots the moment
 * the number behind it moves. This measures the real behaviour and then asserts the document says
 * the same thing, so the two cannot drift apart silently.
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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 64 fields")
                // The finding's own point, recorded as an assertion rather than as prose: a clear
                // diagnosis with no code. If someone gives it one, this line fails and the
                // TROUBLESHOOTING paragraph below has to be rewritten in the same commit.
                .hasMessageNotContaining("PRV-");

        String troubleshooting = troubleshooting();
        assertThat(troubleshooting)
                .as("the 64-column ceiling must be written down where a user searching for it looks")
                .contains("BinaryRowWriter")
                .contains("64 columns")
                .contains("at most 64 fields");
    }

    @Test
    void aVeryLongBooleanChainFailsWithTheWholePredicateEchoedAndTheDocumentSaysSo() {
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
        assertThat(stderr).contains("PRV-2010").contains("while converting");
        // The defect inside the defect: the refusal is longer than the query. Pinned as a floor on
        // absurdity rather than an exact size, so that a fix in pravaha-sql that summarises the
        // predicate fails here and prompts the document to be corrected with it.
        assertThat(stderr.length())
                .as("the whole predicate is interpolated verbatim: %d characters of stderr", stderr.length())
                .isGreaterThan(50_000);

        assertThat(troubleshooting())
                .as("the third failure mode of a large boolean expression must be written down")
                .contains("while converting")
                .contains("interpolated verbatim");
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
