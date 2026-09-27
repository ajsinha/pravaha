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
package com.ash.messaging.pravaha.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-053: on the platform the build runs on, both Parquet codecs are present and load. */
class NativeCodecsTest {

    @Test
    void bothParquetCodecsLoadOnTheBuildPlatform() {
        assertThat(NativeCodecs.check())
                .extracting(NativeCodecs.Status::codec, NativeCodecs.Status::state)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("snappy", "loaded"),
                        org.assertj.core.groups.Tuple.tuple("zstd", "loaded"));
        assertThat(NativeCodecs.warning()).isEmpty();
    }
}
