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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What survives a restart, and the values that quietly stop it surviving.
 *
 * <p>CFG-7 and CFG-16 are one defect with two faces: a persistence setting that is wrong in the
 * file and is discovered <em>per registration</em>. The node starts, logs that it is checkpointing,
 * answers {@code UP} on every probe, and refuses every query -- once per client, for ever, rather
 * than once at startup where somebody is looking.
 */
class PersistencePropertiesTest {

    private static PersistenceProperties with(String journal, String checkpointDirectory) {
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(journal);
        persistence.getCheckpoint().setDirectory(checkpointDirectory);
        return persistence;
    }

    @Test
    void theseChecksRunWhileTheBeanIsBuilt_CFG7_CFG16() throws Exception {
        // The placement is the fix. Every value below was already checked somewhere; the defect was
        // that "somewhere" ran per registration, so the failure arrived once per client on a node
        // that had already reported itself healthy.
        assertThat(PersistenceProperties.class
                        .getMethod("validate")
                        .isAnnotationPresent(jakarta.annotation.PostConstruct.class))
                .as("PersistenceProperties.validate must run at bean initialisation")
                .isTrue();
    }

    @Test
    void keepingNoCheckpointsIsRefusedAtStartupRatherThanAtEveryRegistration_CFG16() {
        // CFG-16. `keep` is a plain int with no validation and the bound lives in
        // PeriodicCheckpointer's constructor, which runs per registration -- so one bad integer
        // produced a node that passed every liveness and readiness probe, advertised itself as
        // checkpointing, and could not accept a single query. `pravaha queries` then reported none.
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getCheckpoint().setKeep(0);

        assertThatThrownBy(persistence::validate)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1026")
                .hasMessageContaining("pravaha.checkpoint.keep")
                .hasMessageContaining("every restart starts from nothing");

        persistence.getCheckpoint().setKeep(-1);
        assertThatThrownBy(persistence::validate).isInstanceOf(PravahaException.class);
    }

    @Test
    void theCheckpointCountsThatWorkKeepWorking_CFG16() {
        // V-control: `keep: 1` leaves one file and `keep: 5` five, both measured, so the bound has
        // to admit them.
        for (int keep : new int[] {1, 3, 5, Integer.MAX_VALUE}) {
            PersistenceProperties persistence = new PersistenceProperties();
            persistence.getCheckpoint().setKeep(keep);
            assertThatCode(persistence::validate).as("keep: %d", keep).doesNotThrowAnyException();
        }
    }

    @Test
    void aNonPositiveCheckpointIntervalOrTimeoutIsRefused_CFG15() {
        PersistenceProperties zeroInterval = new PersistenceProperties();
        zeroInterval.getCheckpoint().setInterval(Duration.ZERO);
        assertThatThrownBy(zeroInterval::validate)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("pravaha.checkpoint.interval");

        PersistenceProperties negativeTimeout = new PersistenceProperties();
        negativeTimeout.getCheckpoint().setTimeout(Duration.ofSeconds(-1));
        assertThatThrownBy(negativeTimeout::validate)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("pravaha.checkpoint.timeout");
    }

    @Test
    void aCheckpointDirectoryThatIsAFileIsRefusedAtStartup_CFG7(@TempDir Path directory) throws Exception {
        // CFG-7. Pointing pravaha.checkpoint.directory at an existing regular file started a node
        // that logged "checkpointing registered queries under $QA/data/txnA.csv" and then failed
        // every registration with PRV-1041 "cannot create the checkpoint directory .../txnA.csv/QW"
        // -- up, green, and unable to accept work.
        Path file = Files.writeString(directory.resolve("txnA.csv"), "id\n1\n");

        assertThatThrownBy(() -> with("", file.toString()).validate())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4093")
                .hasMessageContaining("pravaha.checkpoint.directory")
                .hasMessageContaining("not a directory");
    }

    @Test
    void aCheckpointDirectoryUnderAParentThatDoesNotExistIsRefused_CFG7(@TempDir Path directory) {
        Path nested = directory.resolve("absent").resolve("ckpt");

        assertThatThrownBy(() -> with("", nested.toString()).validate())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4093");
    }

    @Test
    void aJournalPathThatIsADirectoryIsRefusedWithACodeRatherThanAnUncheckedIoException_CFG7(@TempDir Path directory)
            throws Exception {
        // CFG-7's third cell. This one DID fail at startup -- and with a bare
        // `UncheckedIOException: cannot read the registry journal at ... / Is a directory`, no
        // error code and no help URL. The one shape that was caught early had the worst message.
        Path asDirectory = Files.createDirectory(directory.resolve("journal.d"));

        assertThatThrownBy(() -> with(asDirectory.toString(), "").validate())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8006")
                .hasMessageContaining("pravaha.registry.journal")
                .hasMessageContaining("one append-only file");
    }

    @Test
    void aJournalInsideADirectoryThatDoesNotExistIsRefused_CFG7(@TempDir Path directory) {
        // The commonest typo, and the one PRV-8006 never fired for: RegistryJournal.append created
        // the missing parent through Files.createDirectories, so a node came up journalling
        // correctly to a path nobody meant while the real journal stayed empty.
        Path journal = directory.resolve("nosuchdir").resolve("registry.journal");

        assertThatThrownBy(() -> with(journal.toString(), "").validate())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8006")
                .hasMessageContaining("created silently");
    }

    @Test
    void thePathsThatAreFineAreLeftAlone_CFG7(@TempDir Path directory) {
        // V-control. A journal file that does not exist yet in a directory that does is the
        // ordinary first start, and a checkpoint directory that does not exist yet under a parent
        // that does is the other one. Neither may be refused.
        PersistenceProperties persistence = with(
                directory.resolve("registry.journal").toString(),
                directory.resolve("ckpt").toString());

        assertThatCode(persistence::validate).doesNotThrowAnyException();
    }

    @Test
    void theCheckpointTimeoutIsBoundForwardedAndReadable_CFG17() {
        // CFG-17. docs/project/qa/cases/CFG.md's assumed fact 9 says pravaha.checkpoint.timeout is a key
        // with a reader and no writer, and that PersistenceProperties.Checkpoint has no timeout
        // field so the key is not even bound. Both halves are false against this build, and this
        // is what stops the case going stale in the other direction: the field exists with a 30s
        // default, and checkpointConfiguration() emits the key in nanoseconds beside interval and
        // keep -- which is what PeriodicCheckpointer.from reads.
        PersistenceProperties persistence = new PersistenceProperties();
        assertThat(persistence.getCheckpoint().getTimeout()).isEqualTo(Duration.ofSeconds(30));

        persistence.getCheckpoint().setTimeout(Duration.ofSeconds(90));
        persistence.getCheckpoint().setInterval(Duration.ofSeconds(2));
        persistence.getCheckpoint().setKeep(4);

        var configuration = persistence.checkpointConfiguration();
        assertThat(configuration.getDuration("pravaha.checkpoint.timeout")).contains(Duration.ofSeconds(90));
        assertThat(configuration.getDuration("pravaha.checkpoint.interval")).contains(Duration.ofSeconds(2));
        assertThat(configuration.getInt("pravaha.checkpoint.keep")).contains(4);
    }
}
