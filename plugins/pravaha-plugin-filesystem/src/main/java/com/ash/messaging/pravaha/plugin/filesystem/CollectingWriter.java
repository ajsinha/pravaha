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
package com.ash.messaging.pravaha.plugin.filesystem;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Wraps a {@link RowWriter} so a caller can run something when the row commits.
 *
 * <p>Exists because the SPI hands a plugin a writer and takes the row back on commit; the plugin
 * never learns where the row went. Tests and simple embedded uses need that hook, and giving them
 * this is better than widening the SPI for their benefit.
 */
public final class CollectingWriter implements RowWriter {

    private final RowWriter delegate;
    private final Runnable onCommit;

    public CollectingWriter(RowWriter delegate, Runnable onCommit) {
        this.delegate = delegate;
        this.onCommit = onCommit;
    }

    @Override
    public StreamSchema schema() {
        return delegate.schema();
    }

    @Override
    public RowWriter setNull(int o) {
        delegate.setNull(o);
        return this;
    }

    @Override
    public RowWriter setBoolean(int o, boolean v) {
        delegate.setBoolean(o, v);
        return this;
    }

    @Override
    public RowWriter setByte(int o, byte v) {
        delegate.setByte(o, v);
        return this;
    }

    @Override
    public RowWriter setShort(int o, short v) {
        delegate.setShort(o, v);
        return this;
    }

    @Override
    public RowWriter setInt(int o, int v) {
        delegate.setInt(o, v);
        return this;
    }

    @Override
    public RowWriter setLong(int o, long v) {
        delegate.setLong(o, v);
        return this;
    }

    @Override
    public RowWriter setFloat(int o, float v) {
        delegate.setFloat(o, v);
        return this;
    }

    @Override
    public RowWriter setDouble(int o, double v) {
        delegate.setDouble(o, v);
        return this;
    }

    @Override
    public RowWriter setDecimal(int o, long hi, long lo) {
        delegate.setDecimal(o, hi, lo);
        return this;
    }

    @Override
    public RowWriter setBytes(int o, byte[] v) {
        delegate.setBytes(o, v);
        return this;
    }

    @Override
    public RowWriter setString(int o, String v) {
        delegate.setString(o, v);
        return this;
    }

    @Override
    public RowWriter weight(long w) {
        delegate.weight(w);
        return this;
    }

    @Override
    public RowWriter eventTimestampNanos(long n) {
        delegate.eventTimestampNanos(n);
        return this;
    }

    @Override
    public RowWriter sequence(long s) {
        delegate.sequence(s);
        return this;
    }

    @Override
    public int commit() {
        int at = delegate.commit();
        onCommit.run();
        return at;
    }

    @Override
    public void abort() {
        delegate.abort();
    }
}
