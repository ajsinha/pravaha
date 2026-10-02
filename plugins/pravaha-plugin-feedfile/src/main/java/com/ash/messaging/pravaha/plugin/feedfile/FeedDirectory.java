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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Which files in a drop directory are ready, and in what order.
 *
 * <p>Both questions have obvious wrong answers that survive testing and fail in production.
 *
 * <p><strong>"It appeared" is not "it is finished".</strong> A partner streaming 400 MB over SFTP
 * will happily let a reader see the first megabyte, and a truncated file decodes perfectly -- it is
 * simply short, which no schema check catches. Three policies, in declining order of how much they
 * can be trusted:
 *
 * <ul>
 *   <li>{@link Completion#MARKER} -- a sibling {@code .done} file. The only one that is a promise
 *       rather than an inference, because the writer made it.
 *   <li>{@link Completion#STABLE} -- unmodified for a quiet period. A heuristic: a slow or stalled
 *       transfer looks exactly like a finished one, so this is <em>at-least-once at best</em> and
 *       says so in the capabilities.
 *   <li>{@link Completion#IMMEDIATE} -- process on sight. Correct only when files arrive by atomic
 *       rename, which is the case for most local producers and no remote ones.
 * </ul>
 *
 * <p><strong>Files have no inherent order</strong>, and guessing one is a correctness bug rather
 * than an inefficiency: a feed of daily deltas applied out of order produces a wrong answer with no
 * error anywhere. The order is therefore declared, and ties break on name so that two files with the
 * same timestamp -- which happens constantly -- still have a total order rather than whatever the
 * filesystem felt like returning.
 */
final class FeedDirectory {

    /** How a file is judged to be completely written. */
    enum Completion {
        MARKER,
        STABLE,
        IMMEDIATE
    }

    /** The order files are consumed in. */
    enum Order {
        NAME,
        MTIME
    }

    private final Path directory;
    private final PathMatcher matcher;
    private final Completion completion;
    private final Order order;
    private final String markerSuffix;
    private final Duration quietPeriod;

    FeedDirectory(
            Path directory,
            String glob,
            Completion completion,
            Order order,
            String markerSuffix,
            Duration quietPeriod) {
        this.directory = directory;
        this.matcher = directory.getFileSystem().getPathMatcher("glob:" + glob);
        this.completion = completion;
        this.order = order;
        this.markerSuffix = markerSuffix;
        this.quietPeriod = quietPeriod;
    }

    /** Every file that is ready to read, in the declared order. */
    /** Whether {@code name} is still in this directory at all, ready or not. */
    boolean holds(String name) {
        return Files.exists(directory.resolve(name));
    }

    List<Path> ready() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry) && matcher.matches(entry.getFileName()) && isComplete(entry)) {
                    files.add(entry);
                }
            }
        } catch (IOException e) {
            throw new PravahaException(
                    FeedFileErrors.DIRECTORY_UNREADABLE, "cannot list the feed directory " + directory + ": " + e, e);
        }
        files.sort(comparator());
        return files;
    }

    private Comparator<Path> comparator() {
        Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString());
        if (order == Order.NAME) {
            return byName;
        }
        // Modification time first, then name: identical timestamps are common -- a batch of files
        // written in the same second -- and without the tie-break the order would be whatever the
        // filesystem returned, which is not stable across runs and would make offsets meaningless.
        return Comparator.comparingLong(FeedDirectory::modifiedAt).thenComparing(byName);
    }

    private static long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stat " + file, e);
        }
    }

    private boolean isComplete(Path file) {
        return switch (completion) {
            case IMMEDIATE -> true;
            case MARKER -> Files.exists(markerFor(file));
            case STABLE -> {
                long age = System.currentTimeMillis() - modifiedAt(file);
                yield age >= quietPeriod.toMillis();
            }
        };
    }

    /** The completion marker for a file: {@code orders.csv} to {@code orders.csv.done}. */
    Path markerFor(Path file) {
        return file.resolveSibling(file.getFileName() + markerSuffix);
    }

    Path directory() {
        return directory;
    }

    /** Resolves a file name from an offset back to a path, if it is still there. */
    Path resolve(String fileName) {
        return directory.resolve(fileName);
    }
}
