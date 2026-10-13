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
package com.ash.messaging.pravaha.server.folders;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;

/**
 * Writes the declaration files the console, the API and the CLI change a node through (ADR-064).
 *
 * <p><b>The files are the single source of truth.</b> A write puts exactly the file a person would in
 * the configured folder -- staged, read back by the code that reads every declaration file
 * ({@link DeclarationFile#read}), then put in place atomically (a hidden temporary file in the same
 * folder, forced to disk, renamed over the old one) -- and applies it at once through the watcher's own
 * path ({@link DeclarationWatcher#applyNow}), not at the next poll, which would find it unchanged.
 *
 * <p><b>A refused write writes nothing.</b> A file that cannot be read, a literal secret
 * ({@code PRV-1063}), a change to an object a registered query uses ({@code PRV-1065}), a plugin the node
 * cannot load: each is refused before the file is touched where it can be known before, and where it can
 * only be known by applying -- a dimension table that will not open -- the old file is put back and
 * applied again. Switching a stream or connection off ({@code enabled: false}) is never refused for being
 * in use: a query over a disabled stream keeps running and receives nothing until it is enabled.
 *
 * <p>Every write, and every refusal, is audited as {@code config.<kind>.<action>} with who asked.
 */
@Component
public class DeclarationWrites {

    private static final Logger LOG = LoggerFactory.getLogger(DeclarationWrites.class);

    /** What a write does. */
    public enum Action {
        CREATE,
        UPDATE,
        DELETE,
        ENABLE,
        DISABLE;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What a write did, or -- for a dry run -- would do.
     *
     * @param action {@code create}, {@code update}, {@code delete}, {@code enable}, {@code disable}, or
     *     {@code unchanged} when the file already says it
     * @param applied whether the file was written and is in force
     * @param valid whether the write would be (or was) accepted
     * @param path the file written, or that would be
     * @param yaml the file's text after the write: what is, or would be, on disk; null for a delete
     * @param previous the file's text before, or null when there was none
     * @param dependents what uses the object: queries, or for a connection the bindings naming it
     * @param code why it is refused, {@code PRV-nnnn}; null when it is not
     * @param message the refusal, in words; null when it is not refused
     */
    public record Outcome(
            String kind,
            String name,
            String action,
            boolean dryRun,
            boolean applied,
            boolean valid,
            String path,
            @Nullable String yaml,
            @Nullable String previous,
            List<String> dependents,
            @Nullable String code,
            @Nullable String message) {}

    private final ConfigFolders folders;
    private final AuditSink audit;

    public DeclarationWrites(ConfigFolders folders, AuditSink audit) {
        this.folders = folders;
        this.audit = audit;
    }

    /**
     * Creates or replaces {@code name}'s file with {@code yaml}.
     *
     * @param action {@link Action#CREATE} (refused when a file already declares the name) or
     *     {@link Action#UPDATE} (refused when none does)
     * @throws PravahaException when refused and not a dry run; a dry run answers the refusal instead
     */
    public Outcome write(Principal who, DeclarationKind kind, String name, Action action, String yaml, boolean dryRun) {
        if (action != Action.CREATE && action != Action.UPDATE) {
            throw new IllegalArgumentException("write is a create or an update, not " + action);
        }
        return attempt(who, kind, name, action, dryRun, () -> {
            DeclarationWatcher watcher = watcher(kind);
            Optional<Path> existing = existing(watcher, kind, name);
            if (action == Action.CREATE && existing.isPresent()) {
                throw new PravahaException(
                        ConfigErrors.DECLARATION_EXISTS,
                        kind.label() + " '" + name + "' is already declared by " + existing.get()
                                + ". Change it with an update (PUT), or choose another name.");
            }
            if (action == Action.CREATE && watcher.declaredOtherwise(kind, name)) {
                throw notAFile(kind, name);
            }
            if (action == Action.UPDATE && existing.isEmpty()) {
                throw watcher.declaredOtherwise(kind, name) ? notAFile(kind, name) : unknown(kind, name);
            }
            Path path = existing.orElseGet(() -> watcher.folder(kind).path().resolve(name + ".yaml"));
            return change(who, watcher, kind, name, action, path, yaml, dryRun, false);
        });
    }

    /** Removes {@code name}'s file, and so the object -- refused while anything uses it. */
    public Outcome delete(Principal who, DeclarationKind kind, String name, boolean dryRun) {
        return attempt(who, kind, name, Action.DELETE, dryRun, () -> {
            DeclarationWatcher watcher = watcher(kind);
            Path path = existing(watcher, kind, name)
                    .orElseThrow(
                            () -> watcher.declaredOtherwise(kind, name) ? notAFile(kind, name) : unknown(kind, name));
            String previous = read(path);
            List<String> dependents = watcher.users(kind, name);
            if (!dependents.isEmpty()) {
                throw inUse(kind, name, "removed", dependents);
            }
            if (dryRun) {
                return outcome(kind, name, Action.DELETE.label(), true, false, path, null, previous, dependents);
            }
            watcher.exclusively(() -> {
                deleteFile(path);
                PravahaException refused = watcher.applyNow(kind, name);
                if (refused != null) {
                    restore(path, previous);
                    watcher.applyNow(kind, name);
                    throw refused;
                }
                return null;
            });
            return outcome(kind, name, Action.DELETE.label(), false, true, path, null, previous, dependents);
        });
    }

    /**
     * Switches a stream or a connection on or off by writing {@code enabled:} in its file, every other line
     * kept. Never refused for being in use.
     */
    public Outcome enable(Principal who, DeclarationKind kind, String name, boolean enabled, boolean dryRun) {
        Action action = enabled ? Action.ENABLE : Action.DISABLE;
        return attempt(who, kind, name, action, dryRun, () -> {
            if (kind != DeclarationKind.STREAM && kind != DeclarationKind.CONNECTOR) {
                throw new PravahaException(
                        ConfigErrors.FILE_INVALID,
                        "a " + kind.label() + " cannot be switched off; only a stream or a connection can. Remove "
                                + "its file to remove it.");
            }
            DeclarationWatcher watcher = watcher(kind);
            Path path = existing(watcher, kind, name)
                    .orElseThrow(
                            () -> watcher.declaredOtherwise(kind, name) ? notAFile(kind, name) : unknown(kind, name));
            String previous = read(path);
            String next = DeclarationYaml.withEnabled(previous, enabled);
            return change(who, watcher, kind, name, action, path, next, dryRun, true);
        });
    }

    /** The file that declares {@code name} now: the one in force, else one in the folder not applied. */
    public Optional<Path> fileOf(DeclarationKind kind, String name) {
        DeclarationWatcher watcher = folders.watcher().orElse(null);
        return watcher == null ? Optional.empty() : existing(watcher, kind, name);
    }

    /** The text of the file that declares {@code name}, for an editor: secrets are {@code ${ENV}}, never values. */
    public Optional<String> textOf(DeclarationKind kind, String name) {
        return fileOf(kind, name).map(DeclarationWrites::read);
    }

    private Outcome change(
            Principal who,
            DeclarationWatcher watcher,
            DeclarationKind kind,
            String name,
            Action action,
            Path path,
            String yaml,
            boolean dryRun,
            boolean switchOnly) {
        String previous = Files.isRegularFile(path) ? read(path) : null;
        if (previous != null && previous.equals(yaml)) {
            return outcome(kind, name, "unchanged", dryRun, !dryRun, path, yaml, previous, watcher.users(kind, name));
        }
        DeclarationFile staged = stage(watcher, kind, path, yaml);
        watcher.check(staged);
        List<String> dependents = action == Action.CREATE ? List.of() : watcher.users(kind, name);
        DeclarationFile inForce = watcher.files(kind).get(name);
        boolean onlyEnabled =
                switchOnly || (inForce != null && inForce.canonical().equals(staged.canonical()));
        if (!dependents.isEmpty() && !onlyEnabled) {
            throw inUse(kind, name, "changed", dependents);
        }
        if (dryRun) {
            return outcome(kind, name, action.label(), true, false, path, yaml, previous, dependents);
        }
        watcher.exclusively(() -> {
            writeAtomically(path, yaml);
            PravahaException refused = watcher.applyNow(kind, name);
            if (refused != null) {
                restore(path, previous);
                watcher.applyNow(kind, name);
                throw refused;
            }
            return null;
        });
        LOG.info("{} '{}' {} by {}: {}", kind.label(), name, action.label() + "d", who.id(), path);
        return outcome(kind, name, action.label(), false, true, path, yaml, previous, dependents);
    }

    /** Runs a write, auditing it; a dry run's refusal is answered, a real one is thrown. */
    private Outcome attempt(
            Principal who,
            DeclarationKind kind,
            String name,
            Action action,
            boolean dryRun,
            java.util.function.Supplier<Outcome> work) {
        String event = "config." + kind.label() + "." + action.label();
        try {
            if (!DeclarationFile.isValidName(name)) {
                throw new PravahaException(
                        ConfigErrors.FILE_NAME_INVALID,
                        "'" + name + "' is not a name a " + kind.label() + " file can have: use letters, digits, "
                                + "'_' and '-', at most 128.");
            }
            Outcome done = work.get();
            if (!dryRun) {
                audit.record(
                        new AuditEvent(Instant.now(), who, event, name, true, done.action(), Optional.of(done.path())));
            }
            return done;
        } catch (PravahaException e) {
            String message = message(e);
            if (!dryRun) {
                audit.record(new AuditEvent(
                        Instant.now(), who, event, name, false, e.errorCode().code(), Optional.of(message)));
                throw e;
            }
            String path = fileOf(kind, name).map(Path::toString).orElse("");
            return new Outcome(
                    kind.label(),
                    name,
                    action.label(),
                    true,
                    false,
                    false,
                    path,
                    null,
                    null,
                    dependentsOf(kind, name),
                    e.errorCode().code(),
                    message);
        }
    }

    private List<String> dependentsOf(DeclarationKind kind, String name) {
        try {
            return folders.watcher().map(w -> w.users(kind, name)).orElse(List.of());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static Outcome outcome(
            DeclarationKind kind,
            String name,
            String action,
            boolean dryRun,
            boolean applied,
            Path path,
            @Nullable String yaml,
            @Nullable String previous,
            List<String> dependents) {
        return new Outcome(
                kind.label(),
                name,
                action,
                dryRun,
                applied,
                true,
                path.toString(),
                yaml,
                previous,
                List.copyOf(dependents),
                null,
                null);
    }

    private DeclarationWatcher watcher(DeclarationKind kind) {
        return folders.watcher()
                .orElseThrow(() -> new PravahaException(
                        ConfigErrors.FOLDER_NOT_WRITABLE,
                        "this node has no configuration folders to write a " + kind.label() + " file into: it was "
                                + "made without pravaha.config.* (an embedded or builder-made node), or it has not "
                                + "started. Declare it in configuration instead."));
    }

    /** The file declaring {@code name}: the one in force, else one in the folder that was not applied. */
    private static Optional<Path> existing(DeclarationWatcher watcher, DeclarationKind kind, String name) {
        DeclarationFile inForce = watcher.files(kind).get(name);
        if (inForce != null && Files.isRegularFile(inForce.path())) {
            return Optional.of(inForce.path());
        }
        Path folder = watcher.folder(kind).path();
        for (String extension : List.of(".yaml", ".yml")) {
            Path candidate = folder.resolve(name + extension);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads {@code yaml} as the file at {@code path} would be read, without putting it there: staged in a
     * hidden folder beside it, which no look lists. A refusal names {@code path}, not the staging copy.
     */
    private DeclarationFile stage(DeclarationWatcher watcher, DeclarationKind kind, Path path, String yaml) {
        Path folder = watcher.folder(kind).path();
        Path staging = null;
        try {
            Files.createDirectories(folder);
            staging = Files.createTempDirectory(folder, ".pravaha-write-");
            Path staged = staging.resolve(String.valueOf(path.getFileName()));
            Files.writeString(staged, yaml, StandardCharsets.UTF_8);
            try {
                DeclarationFile file = DeclarationFile.read(kind, staged, folders.placeholders());
                return new DeclarationFile(
                        file.kind(),
                        file.name(),
                        path,
                        file.canonical(),
                        file.stream(),
                        file.source(),
                        file.sink(),
                        file.enabled());
            } catch (PravahaException e) {
                throw new PravahaException(e.errorCode(), message(e).replace(staged.toString(), path.toString()));
            }
        } catch (IOException e) {
            throw notWritable(folder, e);
        } finally {
            deleteTree(staging);
        }
    }

    private static void writeAtomically(Path path, String text) {
        Path folder = java.util.Objects.requireNonNull(path.toAbsolutePath().getParent());
        Path temporary = null;
        try {
            Files.createDirectories(folder);
            temporary = Files.createTempFile(folder, "." + path.getFileName() + ".", ".tmp");
            try (FileChannel channel =
                    FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            forceDirectory(folder);
        } catch (IOException e) {
            throw notWritable(folder, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    LOG.debug("could not remove {}", temporary, e);
                }
            }
        }
    }

    /** Makes the rename itself durable, where the platform allows a directory to be forced. */
    private static void forceDirectory(Path folder) {
        try (FileChannel directory = FileChannel.open(folder, StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException | RuntimeException e) {
            LOG.debug("{}: the directory cannot be forced on this platform; the rename is still atomic", folder);
        }
    }

    private static void restore(Path path, @Nullable String previous) {
        if (previous == null) {
            deleteFile(path);
        } else {
            writeAtomically(path, previous);
        }
    }

    private static void deleteFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw notWritable(
                    java.util.Objects.requireNonNull(path.toAbsolutePath().getParent()), e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PravahaException(
                    ConfigErrors.FILE_UNREADABLE,
                    path + " cannot be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static void deleteTree(@Nullable Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> all =
                    new ArrayList<>(walk.sorted(Comparator.reverseOrder()).toList());
            for (Path each : all) {
                Files.deleteIfExists(each);
            }
        } catch (IOException e) {
            LOG.debug("could not remove the staging folder {}", root, e);
        }
    }

    private static PravahaException notWritable(Path folder, IOException e) {
        return new PravahaException(
                ConfigErrors.FOLDER_NOT_WRITABLE,
                folder + " cannot be written by the node (" + e.getClass().getSimpleName() + "). Give the folder to "
                        + "the user the node runs as, or point pravaha.config.dir elsewhere. Nothing was written.");
    }

    private static PravahaException unknown(DeclarationKind kind, String name) {
        return new PravahaException(
                ConfigErrors.DECLARATION_UNKNOWN,
                "no file in the " + kind.plural() + " folder declares " + kind.label() + " '" + name + "'.");
    }

    private static PravahaException notAFile(DeclarationKind kind, String name) {
        return new PravahaException(
                ConfigErrors.DECLARATION_NOT_A_FILE,
                kind.label() + " '" + name + "' is declared inline under " + kind.inlineBlock() + " in application.yaml"
                        + (kind == DeclarationKind.STREAM ? " or by POST /api/v1/streams" : "")
                        + ", not by a file, so there is no file to change. Move it into "
                        + kind.plural() + "/" + name + ".yaml to manage it here.");
    }

    private static PravahaException inUse(DeclarationKind kind, String name, String what, List<String> users) {
        return new PravahaException(
                ConfigErrors.FILE_IN_USE,
                kind.label() + " '" + name + "' is in use by " + String.join(", ", users) + ", so it cannot be "
                        + what + " while they run. Nothing was written. Drop "
                        + (users.size() == 1 ? "it" : "them") + " first"
                        + (kind == DeclarationKind.STREAM || kind == DeclarationKind.CONNECTOR
                                ? ", or disable the " + kind.label()
                                        + " instead: that is never refused for being in use"
                                : "")
                        + ".");
    }

    /** A refusal's text without its leading code, which the response carries on its own. */
    static String message(PravahaException e) {
        ErrorCode code = e.errorCode();
        String message = String.valueOf(e.getMessage());
        String prefix = code.code() + "  ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }
}
