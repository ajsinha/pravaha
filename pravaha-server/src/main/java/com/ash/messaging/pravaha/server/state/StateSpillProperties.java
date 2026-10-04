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
package com.ash.messaging.pravaha.server.state;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import com.ash.messaging.pravaha.runtime.exec.SpillSettings;

/**
 * {@code pravaha.state.spill.*}: ADR-037 item B2's overflow tier, as an operator turns it on.
 *
 * <pre>
 * pravaha:
 *   state:
 *     spill:
 *       enabled: true
 *       directory: /opt/pravaha/data/spill
 *       max-overflow-slabs: 512
 *       compaction-threshold: 0.5
 *       max-bytes: 20GB
 * </pre>
 *
 * <p>{@code prefix = "pravaha.state.spill"} directly, the way {@code LaneProperties} binds {@code
 * pravaha.lane.*} -- not {@code prefix = "pravaha"} with a field named {@code state} nesting a field
 * named {@code spill}. Both shapes exist in this codebase; the one that would go wrong here is the
 * one {@code SourceBindingProperties}' own javadoc names, and it is specifically about a field whose
 * children are caller-chosen keys (a stream name, a table name) needing the field's own name to
 * become part of the path. {@code enabled}/{@code directory}/{@code max-overflow-slabs} are fixed
 * names, not caller-chosen ones, so this block has nothing for that trap to catch -- but it is
 * spelled out here because getting it backwards binds nothing and fails silently either way.
 *
 * <p>Off by default -- the same choice {@code pravaha.pgwire.enabled} makes for TLS, and every other
 * capability in this codebase makes the same way: enabled, disabled, and every setting, all from
 * configuration, never a system property read only by this one thing.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.state.spill")
public class StateSpillProperties {

    /**
     * Explicit tri-state, not a primitive {@code boolean}. An operator who writes {@code enabled:
     * false} must get exactly that, whether or not a directory happens to be set underneath it --
     * an explicit answer always wins over an inferred one, in both directions. Only when this is
     * absent (unset in {@code application.yaml}) does {@link #resolvedEnabled()} fall back to
     * inferring from whether a directory was given. Getting this backwards -- inferring "on"
     * whenever any option is present, so an explicit {@code enabled: false} is silently overridden
     * by a directory left over from a previous configuration -- is a mistake this project's own
     * connector TLS loader made once already; it is not repeated here.
     */
    private @Nullable Boolean enabled;

    /** Where slab files are created. Required, and validated by {@link SpillSettings}, when this
     * resolves to enabled. */
    private String directory = "";

    /** The ceiling on overflow slabs one state store may hold at once once spilling is enabled. */
    private int maxOverflowSlabs = 512;

    /**
     * How much of a store's overflow tier must be free before its sparse slabs are compacted away
     * and their files released (ADR-044). Half, by default: a store holding twice the disk its live
     * state needs is worth a pass; one holding a little more is not.
     */
    private double compactionThreshold = com.ash.messaging.pravaha.state.RowStore.DEFAULT_COMPACTION_THRESHOLD;

    /**
     * The node's disk budget for spilled state, across every query (ADR-044): the most overflow slab
     * mapped at once. {@code 0}, the default, is no quota beyond the filesystem's own free space,
     * which is checked before every slab either way. A size -- {@code 20GB}, {@code 512MB} -- or a
     * plain number of bytes.
     *
     * <p>Alongside {@link #maxOverflowSlabs}, not instead of it. The two bound different things: that
     * one is a ceiling per state store, in its own slab size, so that one runaway join cannot take the
     * whole budget; this one is the directory's, in the unit a disk is sized in.
     */
    private DataSize maxBytes = DataSize.ofBytes(0);

    public @Nullable Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(@Nullable Boolean enabled) {
        this.enabled = enabled;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory == null ? "" : directory;
    }

    public int getMaxOverflowSlabs() {
        return maxOverflowSlabs;
    }

    public void setMaxOverflowSlabs(int maxOverflowSlabs) {
        this.maxOverflowSlabs = maxOverflowSlabs;
    }

    public DataSize getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(DataSize maxBytes) {
        this.maxBytes = maxBytes == null ? DataSize.ofBytes(0) : maxBytes;
    }

    public double getCompactionThreshold() {
        return compactionThreshold;
    }

    public void setCompactionThreshold(double compactionThreshold) {
        this.compactionThreshold = compactionThreshold;
    }

    /**
     * Whether spilling is on: {@link #enabled} decides outright when it is present, in either
     * direction; absent, it is inferred from whether a directory was configured. An operator who
     * sets a directory without ever writing {@code enabled} gets spilling turned on by that alone,
     * which is the one inference this makes -- and the only one, since every other combination has
     * an explicit flag to consult instead.
     */
    public boolean resolvedEnabled() {
        return enabled != null ? enabled : !directory.isBlank();
    }

    /** This configuration, resolved into the plain settings {@code pravaha-runtime} consumes. */
    public SpillSettings toSpillSettings() {
        return resolvedEnabled()
                ? new SpillSettings(true, directory, maxOverflowSlabs, compactionThreshold, maxBytes.toBytes())
                : SpillSettings.DISABLED;
    }
}
