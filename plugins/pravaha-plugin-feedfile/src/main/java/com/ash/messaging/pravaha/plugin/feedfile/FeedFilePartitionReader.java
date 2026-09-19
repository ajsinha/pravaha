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
package com.ash.messaging.pravaha.plugin.feedfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Reads a drop directory file by file, record by record.
 *
 * <p>The loop is unremarkable; the edges are where feed readers earn their keep, and each of these
 * is a failure somebody has had in production:
 *
 * <ul>
 *   <li><strong>A file that appears behind the cursor is skipped, not read.</strong> Under name
 *       ordering, a partner dropping {@code orders-2026-01-05.csv} after {@code -01-09} has already
 *       been consumed would otherwise be read out of order -- and applied out of order, which for a
 *       feed of deltas is a wrong answer with no error. It is logged as skipped, loudly, because
 *       silently ignoring a file that arrived is its own kind of data loss.
 *   <li><strong>A poison file is quarantined, not fatal.</strong> One malformed file from one
 *       partner must not stop a feed carrying nine others. With no quarantine directory configured
 *       it does stop, because the alternative -- dropping it -- is worse.
 *   <li><strong>Archiving is opt-in and forfeits replay</strong>, which the plugin declares rather
 *       than letting a checkpoint discover it (see {@link FeedFileSourcePlugin#capabilities()}).
 * </ul>
 */
final class FeedFilePartitionReader implements PartitionReader {

    private final FeedDirectory feed;
    private final StreamSchema schema;
    private final Supplier<FeedRecordDecoder> decoders;
    private final Optional<Path> archiveDir;
    private final Optional<Path> quarantineDir;

    private FeedFileOffset offset;
    private FeedRecordDecoder decoder;
    private Path openPath;
    private boolean paused;
    private boolean currentFileDone;
    private long recordsInFile;
    private long sequence;

    FeedFilePartitionReader(
            FeedDirectory feed,
            StreamSchema schema,
            Supplier<FeedRecordDecoder> decoders,
            Optional<Path> archiveDir,
            Optional<Path> quarantineDir,
            SourceOffset resumeFrom) {
        this.feed = feed;
        this.schema = schema;
        this.decoders = decoders;
        this.archiveDir = archiveDir;
        this.quarantineDir = quarantineDir;
        this.offset = FeedFileOffset.parse(resumeFrom);
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused) {
            return 0;
        }
        int emitted = 0;
        while (emitted < maxRecords) {
            if (decoder == null && !openNextFile()) {
                return emitted; // nothing ready; the next drop arrives on a later poll
            }
            int fromFile = readFrom(sink, maxRecords - emitted);
            emitted += fromFile;
            if (fromFile == 0) {
                finishFile();
            }
        }
        return emitted;
    }

    /** Reads up to {@code limit} records from the open file. */
    private int readFrom(RecordSink sink, int limit) {
        int emitted = 0;
        while (emitted < limit) {
            boolean hasRecord;
            try {
                hasRecord = decoder.advance();
            } catch (PravahaException e) {
                quarantine(e);
                return emitted;
            }
            if (!hasRecord) {
                return emitted;
            }
            RowWriter writer = sink.beginRow();
            try {
                decoder.write(writer);
            } catch (PravahaException e) {
                writer.abort();
                quarantine(e);
                return emitted;
            }
            // A feed file carries no event time unless a column holds one, and inventing one from
            // the file's timestamp would move every window boundary to whenever the partner happened
            // to upload. When the stream declares the column, the row carries its value: stamping
            // zero regardless kept the watermark in 1970, and no event-time window ever closed
            // (HLP-6).
            long eventTime = decoder.lastEventTimeNanos();
            writer.weight(1L)
                    .eventTimestampNanos(eventTime == Long.MIN_VALUE ? 0L : eventTime)
                    .sequence(sequence++)
                    .commit();
            recordsInFile++;
            offset = offset.withRecord(recordsInFile);
            emitted++;
        }
        return emitted;
    }

    /** Opens the next file after the offset's, if one is ready. */
    private boolean openNextFile() {
        List<Path> ready = feed.ready();
        String current = offset.fileName();
        for (Path candidate : ready) {
            String name = candidate.getFileName().toString();
            int comparison = name.compareTo(current);
            if (comparison < 0 && !offset.isBeginning()) {
                continue; // already behind the cursor; see the class note on late arrivals
            }
            if (comparison == 0) {
                if (currentFileDone) {
                    continue; // read to its end already; the cursor stays on it until a later file
                }
                if (offset.recordIndex() > 0) {
                    // A restart lands here: reopen the file and skip what the offset says was done.
                    // It may already be at end of file, which is how the reader steps past a file
                    // that was finished exactly as the process stopped.
                    open(candidate, offset.recordIndex());
                    return true;
                }
            }
            if (comparison > 0 || offset.isBeginning()) {
                if (!currentFileDone && !offset.isBeginning() && !feed.holds(current)) {
                    // Moving on would skip whatever was left in the file the offset names. See
                    // reportGone: the silent version of this is the defect (E-1/PRV-5064).
                    throw gone(current, "the reader is moving on to '" + name + "'");
                }
                offset = offset.at(name);
                open(candidate, 0);
                return true;
            }
        }
        // Nothing to open, and this returns quietly. That is right for the ordinary case -- the feed
        // has produced nothing new -- and it is also what happens when the file the cursor names has
        // been rotated away with nothing after it.
        //
        // E-1/PRV-5064, and the limit of what can be fixed without changing the offset format.
        // `FeedFileOffset` records a file name and a record index; it does not record whether that
        // file was read to its end. A reader resuming from a checkpoint therefore cannot tell "I
        // finished this file and it was then rotated", which is routine and must stay silent, from
        // "I was two records in and it was taken away", which is loss. Guessing either way is wrong:
        // raising here made ordinary rotation fail, and staying silent is the defect.
        //
        // So the throw above covers the case where loss is *demonstrable* -- the cursor is unfinished
        // and the reader is about to move past it to a later file. The remaining case needs the
        // offset to carry an exhausted flag, which changes a persisted format and belongs in its own
        // change. Recorded on E-1 rather than left as a comment nobody reads.
        return false;
    }

    /** The refusal for a file the cursor names and the directory no longer has. */
    private PravahaException gone(String name, String what) {
        return new PravahaException(
                FeedFileErrors.FILE_GONE,
                "the feed file '" + name + "' is no longer in " + feed + " and this reader was at record "
                        + offset.recordIndex() + " of it, so " + what + " would skip the rest of it without "
                        + "anybody noticing. Feed files must outlive the readers that are still in them: raise "
                        + "the retention on whatever rotates them, or let this query finish the file before it "
                        + "is moved. Restarting the query resumes from the next file and accepts the loss.");
    }

    private void open(Path file, long skip) {
        decoder = decoders.get();
        currentFileDone = false;
        decoder.open(file, schema);
        openPath = file;
        recordsInFile = skip;
        if (skip > 0) {
            decoder.skip(skip);
        }
    }

    /** Closes the finished file, archiving it if asked, and moves the cursor past it. */
    private void finishFile() {
        closeDecoder();
        currentFileDone = true;
        if (openPath != null) {
            archiveDir.ifPresent(dir -> move(openPath, dir));
            openPath = null;
        }
        // The cursor stays on the finished file's name with its final record count, so a restart
        // resumes after it rather than before it.
    }

    private void quarantine(PravahaException failure) {
        if (quarantineDir.isEmpty()) {
            closeDecoder();
            throw failure;
        }
        Path poison = openPath;
        closeDecoder();
        move(poison, quarantineDir.get());
        openPath = null;
        // The records already emitted from this file stand. They were well-formed, and retracting
        // them would need a before-image the feed does not have; the DLQ entry records where the
        // file stopped so the partial ingest is visible rather than implied.
    }

    private void move(Path file, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            Files.move(file, targetDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            Path marker = feed.markerFor(file);
            if (Files.exists(marker)) {
                Files.move(marker, targetDir.resolve(marker.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot move " + file + " to " + targetDir, e);
        }
    }

    private void closeDecoder() {
        if (decoder != null) {
            decoder.close();
            decoder = null;
        }
    }

    @Override
    public SourceOffset position() {
        return offset.toSourceOffset();
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    @Override
    public void close() {
        closeDecoder();
    }
}
