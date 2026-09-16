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
package com.ash.messaging.pravaha.security;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * An audit trail an operator can actually read: one JSON object per line, in a file.
 *
 * <p>CFG-23. {@link AuditSink.InMemory} is correct, complete and unreachable -- nothing in any
 * {@code src/main} calls {@code events()}, so {@code pravaha.security.audit: memory} is a setting
 * that accepts every decision and exposes them to nobody. An operator asked "who read payroll" had
 * no way to answer it from a running node.
 *
 * <p><strong>Why a file rather than an endpoint.</strong> An endpoint that lists who-read-what is
 * itself a disclosure surface -- it carries every principal id, every view name and the SQL text
 * that made SX-11 a breach rather than an inconvenience -- so it would need its own authorization,
 * and this codebase has none to give it. {@link SecurityPolicy} answers three questions: may this
 * principal read <em>this view</em>, administer <em>this view</em>, register a query. None of them
 * means "may read the audit trail". Answering it by passing a pseudo-view name such as
 * {@code __audit} to {@code mayRead} would be a check applied to the wrong noun, which is the single
 * commonest defect in this project's own register -- and under the default {@code permissive} policy
 * it would return ALLOW to everybody, publishing the trail to every caller on the node.
 *
 * <p>A file needs no such invention. The operating system already answers "who may read this": the
 * file is created {@code rw-------} for the user the node runs as, and a deployment that wants
 * someone else to read it says so with the tools it already uses for every other sensitive file.
 * The trail also survives the process, which an in-memory sink never can, and that is what an audit
 * is for.
 *
 * <p><strong>It must not fail the query it is auditing.</strong> {@link #record} hands the event to
 * a bounded queue and returns; one daemon thread does the writing. A full queue drops, and says so
 * in the file itself -- a gap in an audit trail that nothing records is a trail that lies, so the
 * next line written after a drop is a {@code audit.dropped} marker carrying the count. Failures
 * after the file is open (a full disk, a revoked permission) are counted and reported through the
 * {@code problems} consumer the node wires to its log, never thrown at the caller.
 *
 * <p><strong>Refused at startup, not at the first event.</strong> The constructor opens the file. A
 * path that cannot be written is {@code PRV-7004 SECURITY_MISCONFIGURED} before the node serves
 * anything, because a node that starts believing it is auditing and writes nowhere is the outcome
 * CFG-5 and CFG-23 both produced from opposite ends.
 */
public final class FileAuditSink implements AuditSink, AutoCloseable {

    /** 64 MiB before rotating: large enough to be a day of a busy node, small enough to move. */
    public static final long DEFAULT_ROTATE_BYTES = 64L * 1024 * 1024;

    /** How many rotated generations are kept. */
    public static final int DEFAULT_KEEP = 5;

    /**
     * Events that may wait for the disk.
     *
     * <p>Bounded, because the alternative to dropping is either blocking the query path or growing
     * until the process dies -- and an audit sink that takes the engine down with it turns an
     * observability problem into an outage.
     */
    private static final int QUEUE_CAPACITY = 8192;

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final Path path;
    private final long rotateBytes;
    private final int keep;
    private final Consumer<String> problems;

    private final BlockingQueue<AuditEvent> pending = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread writer;
    private final Thread flushOnExit;

    private OutputStream out;
    private long bytesInFile;
    private long reportedDrops;
    private boolean reportedFailure;

    public FileAuditSink(Path path) {
        this(path, DEFAULT_ROTATE_BYTES, DEFAULT_KEEP, null);
    }

    /**
     * @param path the file to append to; its directory is created if missing
     * @param rotateBytes rotate once the file passes this size
     * @param keep how many rotated generations to keep, oldest deleted
     * @param problems where a write failure is reported -- the node passes its logger. Defaults to
     *     standard error rather than to nothing: this module deliberately has no logging dependency
     *     (it sits on the path of every query), and an audit sink failing in complete silence is the
     *     defect this class exists to close
     */
    public FileAuditSink(Path path, long rotateBytes, int keep, Consumer<String> problems) {
        this.path = path.toAbsolutePath();
        this.rotateBytes = Math.max(4096L, rotateBytes);
        this.keep = Math.max(0, keep);
        this.problems = problems == null ? message -> System.err.println("pravaha audit: " + message) : problems;
        open();
        this.writer = new Thread(this::drainForever, "pravaha-audit-file");
        this.writer.setDaemon(true);
        this.writer.start();
        // A kill -TERM should not lose the last few decisions a node made, which are the ones an
        // investigation after a restart is most likely to want.
        this.flushOnExit = new Thread(this::close, "pravaha-audit-file-flush");
        Runtime.getRuntime().addShutdownHook(flushOnExit);
    }

    @Override
    public void record(AuditEvent event) {
        if (closed.get()) {
            accepted.incrementAndGet();
            dropped.incrementAndGet();
            return;
        }
        accepted.incrementAndGet();
        if (!pending.offer(event)) {
            // Dropped rather than blocked. The count is written into the file as soon as there is
            // room, so the gap is part of the record instead of being invisible in it.
            dropped.incrementAndGet();
        }
    }

    /** Events written to the file. */
    public long writtenEvents() {
        return written.get();
    }

    /** Events the queue had no room for. Non-zero means the trail has recorded gaps. */
    public long droppedEvents() {
        return dropped.get();
    }

    /** Write failures since the file was opened. */
    public long failedWrites() {
        return failed.get();
    }

    /** The file being appended to. */
    public Path path() {
        return path;
    }

    /**
     * Blocks until everything recorded so far is on disk.
     *
     * <p>For tests and for an operator who has just run something and wants to read the trail. The
     * query path never calls it.
     */
    public void flush(java.time.Duration timeout) {
        // Waits on the *accounted* count rather than on the queue being empty. An event the writer
        // has taken out of the queue and not yet written is in neither place, so "the queue is
        // empty" becomes true a moment before the file is complete -- a flaky test at best, and for
        // an operator reading the trail straight after a refusal, a line that is not there yet.
        long target = accepted.get();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (written.get() + dropped.get() < target && System.nanoTime() < deadline) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        synchronized (this) {
            try {
                if (out != null) {
                    out.flush();
                }
            } catch (IOException e) {
                failed.incrementAndGet();
                reportFailure(e);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            // Unregistered so that a process creating and closing sinks -- a test suite, an embedded
            // engine restarted in place -- does not accumulate hooks for sinks that are gone.
            Runtime.getRuntime().removeShutdownHook(flushOnExit);
        } catch (IllegalStateException alreadyShuttingDown) {
            // We are the hook, or another one is running. Nothing to remove.
        }
        writer.interrupt();
        try {
            writer.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            // Whatever is still queued: an audit event held in memory at shutdown is an event that
            // never happened as far as anyone reading the file is concerned.
            List<AuditEvent> remaining = new ArrayList<>();
            pending.drainTo(remaining);
            for (AuditEvent event : remaining) {
                writeLine(lineOf(event));
            }
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                    out = null;
                }
            } catch (IOException e) {
                failed.incrementAndGet();
                reportFailure(e);
            }
        }
    }

    // -------------------------------------------------------------------------------------------

    private void drainForever() {
        while (!closed.get()) {
            try {
                AuditEvent event = pending.poll(200, TimeUnit.MILLISECONDS);
                if (event == null) {
                    synchronized (this) {
                        if (out != null) {
                            out.flush();
                        }
                    }
                    continue;
                }
                synchronized (this) {
                    writeLine(lineOf(event));
                    if (pending.isEmpty() && out != null) {
                        out.flush();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                failed.incrementAndGet();
                reportFailure(e);
            }
        }
    }

    /** Appends one line, rotating first if this file has had enough. */
    private void writeLine(String line) {
        if (out == null) {
            return;
        }
        try {
            long gap = dropped.get() - reportedDrops;
            if (gap > 0) {
                reportedDrops = dropped.get();
                byte[] marker = ("{\"at\":\"" + Instant.now() + "\",\"event\":\"audit.dropped\",\"count\":" + gap
                                + ",\"reason\":\"the audit queue was full; these decisions were made and not "
                                + "recorded\"}\n")
                        .getBytes(StandardCharsets.UTF_8);
                append(marker);
            }
            append((line + "\n").getBytes(StandardCharsets.UTF_8));
            written.incrementAndGet();
        } catch (IOException e) {
            failed.incrementAndGet();
            reportFailure(e);
        }
    }

    private void append(byte[] bytes) throws IOException {
        if (bytesInFile + bytes.length > rotateBytes && bytesInFile > 0) {
            rotate();
        }
        out.write(bytes);
        bytesInFile += bytes.length;
    }

    /**
     * Moves the current file aside and starts a new one.
     *
     * <p>{@code audit.jsonl.1} is the most recent generation, as every operator already expects from
     * logrotate. Kept generations are bounded because an audit trail that fills the disk stops the
     * node it was auditing.
     */
    private void rotate() throws IOException {
        out.flush();
        out.close();
        out = null;
        for (int generation = keep; generation >= 1; generation--) {
            Path older = sibling(generation);
            if (Files.exists(older, LinkOption.NOFOLLOW_LINKS)) {
                if (generation == keep) {
                    Files.delete(older);
                } else {
                    Files.move(older, sibling(generation + 1), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (keep > 0) {
            Files.move(path, sibling(1), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(path);
        }
        openStream();
    }

    private Path sibling(int generation) {
        return path.resolveSibling(path.getFileName() + "." + generation);
    }

    private void open() {
        try {
            Path directory = path.getParent();
            if (directory != null && !Files.isDirectory(directory)) {
                Files.createDirectories(directory);
            }
            openStream();
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    "pravaha.security.audit is 'file' and '" + path + "' cannot be written: " + e
                            + ". A node that starts believing it is auditing and writes nowhere has no record "
                            + "at all, so this is refused here rather than at the first decision nobody sees.",
                    e);
        }
    }

    private void openStream() throws IOException {
        boolean existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        out = Files.newOutputStream(
                path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        bytesInFile = existed ? Files.size(path) : 0L;
        restrictPermissions();
    }

    /**
     * Owner-only, because this file is the disclosure surface an endpoint would have been.
     *
     * <p>It holds every principal id that asked for anything and the SQL text they asked with --
     * account numbers included, which is what made the SX-11 listing a breach. World-readable under
     * a default umask would hand that to every login on the box.
     */
    private void restrictPermissions() {
        try {
            if (Files.getFileStore(path)
                    .supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(path, OWNER_ONLY);
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Reported, not fatal: a filesystem without POSIX permissions (Windows, some network
            // mounts) is a real deployment, and refusing to audit at all there would be worse than
            // auditing into a file whose permissions the deployment has to set itself.
            problems.accept("could not restrict permissions on " + path + " to owner-only: " + e);
        }
    }

    private void reportFailure(Exception e) {
        if (!reportedFailure) {
            reportedFailure = true;
            problems.accept("cannot write the audit trail at " + path + ": " + e
                    + ". Decisions are still being made and are no longer being recorded.");
        }
    }

    /**
     * One event as one JSON object.
     *
     * <p>JSON Lines because it is the format every log shipper already reads, and because one
     * self-contained object per line survives truncation: a half-written last line costs the last
     * event and nothing before it.
     *
     * <p>The claims map is deliberately absent. {@code Principal.toString} does not print it for the
     * reason stated there -- claims carry whatever the identity provider put in a token, which in
     * practice includes email addresses and sometimes worse -- and a file written for retention is
     * the last place it should start appearing.
     */
    static String lineOf(AuditEvent event) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"at\":\"").append(event.at()).append('"');
        json.append(",\"principal\":").append(quote(event.principal().id()));
        json.append(",\"tenant\":").append(quote(event.principal().tenant()));
        json.append(",\"roles\":[");
        boolean first = true;
        for (String role : event.principal().roles()) {
            if (!first) {
                json.append(',');
            }
            json.append(quote(role));
            first = false;
        }
        json.append(']');
        json.append(",\"action\":").append(quote(event.action()));
        json.append(",\"target\":").append(quote(event.target()));
        json.append(",\"result\":\"").append(event.allowed() ? "ALLOW" : "DENY").append('"');
        json.append(",\"reason\":").append(quote(event.reason()));
        event.detail().ifPresent(detail -> json.append(",\"detail\":").append(quote(detail)));
        return json.append('}').toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    @Override
    public String toString() {
        return "FileAuditSink[" + path + ", rotate at " + rotateBytes + " bytes, keeping " + keep + "]";
    }
}
