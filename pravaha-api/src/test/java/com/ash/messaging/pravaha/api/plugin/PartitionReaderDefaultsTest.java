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
package com.ash.messaging.pravaha.api.plugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.RowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a source plugin gets for free, and what each default means.
 *
 * <p>Every one of these is a <em>refusal</em> dressed as a boolean, and the direction matters more
 * than the value. A reader that has not implemented dead-lettering must make the engine fail
 * loudly rather than discard records; a reader that cannot decode out of band must make a replay
 * refuse rather than produce a row from bytes nobody can vouch for; and a reader that cannot say
 * where it has read to must make an exactly-once replay refuse rather than risk a duplicate. The
 * safe answer is the default in all three, so a plugin written before any of this existed is
 * conservative by construction.
 */
class PartitionReaderDefaultsTest {

    /** The least a reader can implement: poll, position, pause, resume, close. */
    private static final class Minimal implements PartitionReader {

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            return 0;
        }

        @Override
        public SourceOffset position() {
            return SourceOffset.BEGINNING;
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }

    /** A sink that takes the three-argument rejection and nothing else. */
    private static final class OldSink implements PartitionReader.RecordSink {

        private final List<String> rejected = new ArrayList<>();

        @Override
        public RowWriter beginRow() {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public boolean reject(byte[] raw, String sourceOffset, String reason) {
            rejected.add(sourceOffset + ": " + reason + " [" + new String(raw, StandardCharsets.UTF_8) + "]");
            return true;
        }
    }

    @Test
    void aReaderThatHasNotImplementedTheseRefusesRatherThanGuesses() {
        PartitionReader reader = new Minimal();

        assertThat(reader.decodeOne("812,acme".getBytes(StandardCharsets.UTF_8), "line 812", new OldSink()))
                .as("a replay is refused by name, not answered with a row nobody can vouch for")
                .isFalse();
        assertThat(reader.hasReadPast("line 812"))
                .as("false is 'I cannot say', which makes an exactly-once replay refusable")
                .isFalse();
        assertThat(reader.deliversPartialAggregate()).isFalse();
        // Neither of these does anything, and both must be callable on every reader.
        reader.checkpointed(new SourceOffset("812"));
        reader.close();
    }

    @Test
    void aSinkThatHasNotImplementedDeadLetteringSaysThereIsNowhereToPutIt() {
        PartitionReader.RecordSink sink = () -> {
            throw new UnsupportedOperationException("not needed");
        };

        assertThat(sink.reject("812".getBytes(StandardCharsets.UTF_8), "line 812", "not a number"))
                .as("false means 'nowhere to put it', so the reader fails loudly as it always did")
                .isFalse();
        assertThat(sink.reject("812".getBytes(StandardCharsets.UTF_8), "line 812", "not a number", "PRV-5040"))
                .isFalse();
    }

    @Test
    void aRejectionWithACodeReachesASinkThatOnlyKnowsTheThreeArgumentForm() {
        OldSink sink = new OldSink();

        assertThat(sink.reject("812,acme".getBytes(StandardCharsets.UTF_8), "line 812", "not a number", "PRV-5040"))
                .as("a reader that has a code does not have to check whether the sink wants one")
                .isTrue();
        assertThat(sink.rejected).containsExactly("line 812: not a number [812,acme]");
    }
}
