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
package com.ash.messaging.pravaha.plugin.delta;

import java.util.Locale;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Where a reader is in a Delta table: a version, which <em>part</em> of that version, a file within
 * that part, and a row within that file.
 *
 * <p>The phase is the field that is easy to leave out and impossible to do without. A version's
 * rows are emitted in three different senses -- the initial snapshot's contents, a commit's added
 * files, and a commit's removed files as retractions -- and the file list differs for each. An
 * offset carrying only a version cannot say which, so a reader resuming at "version 5, file 2"
 * would re-emit the whole table at version 5 when it should have emitted the two files that version
 * added. The rows would be real, the weights would be right, and the answer would be wrong.
 *
 * <p>All four fields are reconstructible: Delta versions are immutable, and the file list of a
 * version -- and of the difference between two versions -- is deterministic and sorted by path.
 * That is what lets this source declare replayable offsets rather than merely hope.
 *
 * @param version the table version being emitted
 * @param phase which part of that version
 * @param fileIndex how many files of that part are fully consumed
 * @param rowIndex how many rows of the current file are consumed
 */
public record DeltaOffset(long version, Phase phase, int fileIndex, long rowIndex) {

    /** Which of a version's three senses is being emitted. */
    public enum Phase {
        /** The table's contents at the starting version, all with weight {@code +1}. */
        SNAPSHOT,
        /** Files a commit added, weight {@code +1}. */
        ADDS,
        /** Files a commit removed, weight {@code -1} -- the retraction half of a rewrite. */
        REMOVES
    }

    /** Before anything has been read. */
    public static final DeltaOffset BEGINNING = new DeltaOffset(-1L, Phase.SNAPSHOT, 0, 0L);

    public DeltaOffset {
        if (fileIndex < 0 || rowIndex < 0) {
            throw new IllegalArgumentException("file and row indexes must be non-negative");
        }
        java.util.Objects.requireNonNull(phase, "phase");
    }

    /**
     * Parses a token this plugin wrote.
     *
     * <p>Deliberately strict. A token it cannot parse came from another plugin or another version of
     * this one, and guessing would resume at the wrong place -- silently, and visible only as
     * missing or duplicated rows much later.
     */
    public static DeltaOffset parse(SourceOffset offset) {
        if (offset == null || offset.isBeginning()) {
            return BEGINNING;
        }
        String token = offset.token();
        try {
            String[] parts = token.split(";");
            if (parts.length != 4) {
                throw new IllegalArgumentException("expected four fields");
            }
            return new DeltaOffset(
                    Long.parseLong(field(parts[0], "v")),
                    Phase.valueOf(field(parts[1], "p").toUpperCase(Locale.ROOT)),
                    Integer.parseInt(field(parts[2], "f")),
                    Long.parseLong(field(parts[3], "r")));
        } catch (RuntimeException e) {
            throw new PravahaException(
                    DeltaErrors.MALFORMED_OFFSET,
                    "cannot resume from offset '" + token + "': it is not a Delta offset of the form "
                            + "v=<version>;p=<SNAPSHOT|ADDS|REMOVES>;f=<file>;r=<row>. Resuming from a token "
                            + "this plugin did not write would read from the wrong place without saying so.",
                    e);
        }
    }

    private static String field(String part, String expectedKey) {
        int eq = part.indexOf('=');
        if (eq < 0 || !part.substring(0, eq).equals(expectedKey)) {
            throw new IllegalArgumentException("expected '" + expectedKey + "=', got '" + part + "'");
        }
        return part.substring(eq + 1);
    }

    public SourceOffset toSourceOffset() {
        return new SourceOffset("v=" + version + ";p=" + phase + ";f=" + fileIndex + ";r=" + rowIndex);
    }

    /** True before the first read, when even the starting version is not yet known. */
    public boolean isBeginning() {
        return version < 0;
    }

    public DeltaOffset withRow(long row) {
        return new DeltaOffset(version, phase, fileIndex, row);
    }

    public DeltaOffset nextFile() {
        return new DeltaOffset(version, phase, fileIndex + 1, 0L);
    }

    public DeltaOffset at(long newVersion, Phase newPhase) {
        return new DeltaOffset(newVersion, newPhase, 0, 0L);
    }
}
