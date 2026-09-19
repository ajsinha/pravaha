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
package com.ash.messaging.pravaha.runtime.exec;

import com.ash.messaging.pravaha.state.RowStore;

/**
 * ADR-037 item B2's overflow tier, resolved to one plain, already-decided answer.
 *
 * <p>Deliberately framework-free: this module does not depend on Spring, and should not grow a
 * reason to just because a deployment reads its settings from {@code application.yaml}. The
 * resolution this record does <em>not</em> do -- deciding whether spilling is on when an operator
 * wrote a directory but no explicit {@code enabled} flag -- belongs to whatever reads configuration
 * (see {@code pravaha-server}'s {@code StateSpillProperties}), so that by the time a {@code
 * SpillSettings} exists, "is this on" is no longer a question with more than one answer.
 *
 * @param enabled whether joins compiled after {@link InterpretedPipeline#configureSpill} may spill
 * @param directory where slab files are created; required, and validated, when {@code enabled}
 * @param maxOverflowSlabs the ceiling on overflow slabs one state store may hold at once; required,
 *     and validated, when {@code enabled}
 * @param compactionThreshold how much of a store's overflow tier must be free before its sparse slabs
 *     are compacted away and their files released (ADR-044): a fraction above 0 and at most 1
 */
public record SpillSettings(boolean enabled, String directory, int maxOverflowSlabs, double compactionThreshold) {

    /** No overflow tier: every join refuses at its in-memory ceiling, exactly as it always has. */
    public static final SpillSettings DISABLED = new SpillSettings(false, "", 0);

    /** With the default compaction threshold, {@link RowStore#DEFAULT_COMPACTION_THRESHOLD}. */
    public SpillSettings(boolean enabled, String directory, int maxOverflowSlabs) {
        this(enabled, directory, maxOverflowSlabs, RowStore.DEFAULT_COMPACTION_THRESHOLD);
    }

    public SpillSettings {
        if (!(compactionThreshold > 0 && compactionThreshold <= 1)) {
            throw new IllegalArgumentException("pravaha.state.spill.compaction-threshold is the fraction of the "
                    + "overflow tier that must be free before it is compacted, above 0 and at most 1; got "
                    + compactionThreshold);
        }
        if (enabled && (directory == null || directory.isBlank())) {
            throw new IllegalArgumentException(
                    "pravaha.state.spill.enabled is true but pravaha.state.spill.directory is not set; spilling "
                            + "needs somewhere to spill to");
        }
        if (enabled && maxOverflowSlabs < 1) {
            throw new IllegalArgumentException("pravaha.state.spill.max-overflow-slabs must be at least 1 when "
                    + "spilling is enabled, got " + maxOverflowSlabs);
        }
        directory = directory == null ? "" : directory;
    }
}
