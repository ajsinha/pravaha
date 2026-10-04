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
package com.ash.messaging.pravaha.serving;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The equality index on its own (ADR-055): value to keys, filed by the row it is given and taken
 * out from under the value of the row it is told was there.
 */
class EqualityIndexTest {

    /** Rows of {@code (key, region, amount)}; the index is over {@code region}. */
    private static Object[] row(String key, @Nullable String region, long amount) {
        return new Object[] {key, region, amount};
    }

    private final EqualityIndex<String> index = new EqualityIndex<>(1);

    private List<Object> keysUnder(String region) {
        return index.rowsWith(region).stream().map(values -> values[0]).toList();
    }

    @Test
    void anInsertedRowIsFoundByItsValue() {
        index.put("k1", row("k1", "eu", 10));

        assertThat(keysUnder("eu")).containsExactly("k1");
        assertThat(keysUnder("us")).isEmpty();
        assertThat(index.entries()).isEqualTo(1);
        assertThat(index.values()).isEqualTo(1);
        assertThat(index.ordinal()).isEqualTo(1);
    }

    @Test
    void manyKeysUnderOneValueAreAllFoundAndEachOnce() {
        for (int i = 0; i < 50; i++) {
            index.put("k" + i, row("k" + i, i % 2 == 0 ? "eu" : "us", i));
        }
        // Filing the same key again under the same value replaces its row rather than adding one.
        index.put("k0", row("k0", "eu", 1_000));

        assertThat(keysUnder("eu")).hasSize(25).doesNotHaveDuplicates();
        assertThat(keysUnder("us")).hasSize(25).doesNotHaveDuplicates();
        assertThat(index.entries()).isEqualTo(50);
        assertThat(index.rowsWith("eu").get(0)[2]).as("the newer row").isEqualTo(1_000L);
    }

    @Test
    void aRetractedRowLeavesItsBucketAndAnEmptyBucketLeavesTheIndex() {
        Object[] only = row("k1", "eu", 10);
        index.put("k1", only);
        index.put("k2", row("k2", "eu", 20));

        index.remove("k1", only);
        assertThat(keysUnder("eu")).containsExactly("k2");

        index.remove("k2", row("k2", "eu", 20));
        assertThat(keysUnder("eu")).isEmpty();
        assertThat(index.values()).as("no empty bucket left behind").isZero();
        assertThat(index.entries()).isZero();
    }

    @Test
    void anUpdateOfTheIndexedColumnMovesTheKeyBetweenBuckets() {
        Object[] before = row("k1", "eu", 10);
        index.put("k1", before);

        // What the view does on a commit that changes the column: the row it held out, from under
        // the row's own (old) value, and then the new row in.
        Object[] after = row("k1", "us", 10);
        index.remove("k1", before);
        index.put("k1", after);

        assertThat(keysUnder("eu")).isEmpty();
        assertThat(keysUnder("us")).containsExactly("k1");
        assertThat(index.entries()).isEqualTo(1);
    }

    @Test
    void aRemovalFiledUnderTheWrongValueRemovesNothing() {
        // The failure the view is written to make impossible, shown here so its shape is known:
        // told the NEW row on removal, the index looks in the new bucket, finds nothing, and the
        // entry under the old value survives -- an index answering with a row the view replaced.
        index.put("k1", row("k1", "eu", 10));
        index.remove("k1", row("k1", "us", 10));

        assertThat(keysUnder("eu")).containsExactly("k1");
        assertThat(index.entries()).isEqualTo(1);
    }

    @Test
    void aNullValueIsNeverFiledAndNeverFound() {
        index.put("k1", row("k1", null, 10));
        index.remove("k1", row("k1", null, 10));
        index.remove("absent", row("absent", "eu", 0));

        assertThat(index.entries()).isZero();
        assertThat(index.rowsWith(null)).isEmpty();
    }

    @Test
    void clearEmptiesIt() {
        index.put("k1", row("k1", "eu", 10));
        index.clear();

        assertThat(index.entries()).isZero();
        assertThat(keysUnder("eu")).isEmpty();
        assertThat(index.toString()).contains("0 entries");
    }

    @Test
    void aNegativeColumnIsRefused() {
        assertThatThrownBy(() -> new EqualityIndex<String>(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
