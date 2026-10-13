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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.PlaceholdersResolver;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;

/**
 * One look at the declaration folders, applied to the running node (ADR-064).
 *
 * <ul>
 *   <li>A <b>new file</b> declares its stream, lookup or sink.
 *   <li>A <b>removed file</b> removes it.
 *   <li>A <b>changed file</b> replaces it.
 *   <li>Except: an object a registered query uses -- a stream it reads, a dimension table it joins, a sink
 *       it writes -- is never changed or removed out from under the query. The running definition is
 *       kept, a {@code PRV-1065} warning names the file and the queries, and the change is tried again at
 *       every look, so it is applied at the first look after the last of those queries is dropped.
 *   <li>A file that cannot be used ({@code PRV-1061}..{@code PRV-1064}, or a refusal of what it declares)
 *       is reported, naming the file, and ignored until it is fixed; whatever it declared before keeps
 *       running. Nothing here can take the node down.
 * </ul>
 *
 * <p>Every outcome is logged once when it first happens, audited, and listed by {@link #problems()} until
 * it clears -- which is what {@code GET /api/v1/declarations}, health and the console show.
 */
public final class DeclarationWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(DeclarationWatcher.class);

    /** Who the audit trail says changed the node: its configuration folders, not a caller. */
    static final Principal FOLDERS = new Principal("pravaha-config", "*", Set.of(), Map.of());

    /** What the running node does with one declaration. */
    public interface Target {

        /**
         * Declares, or replaces, what {@code file} declares.
         *
         * @return the registered queries using the object, in which case nothing was changed
         * @throws PravahaException when what the file declares is refused
         */
        List<String> declare(DeclarationFile file);

        /** Removes the object; as {@link #declare}, the users when it is kept. */
        List<String> remove(DeclarationKind kind, String name);

        /** Whether {@code name} is declared on the node by something other than a file (the API). */
        boolean declaredElsewhere(DeclarationKind kind, String name);

        /**
         * Says the files in force changed -- declared, replaced, switched on or off, removed -- so what is
         * derived from all of them (which streams are switched off) can be worked out again. Never refused
         * for being in use: a query over a disabled stream keeps running and receives nothing.
         */
        default void changed() {}

        /** What uses the object now: queries reading a stream, joining a lookup, writing a sink; a connection's bindings. */
        default List<String> users(DeclarationKind kind, String name) {
            return List.of();
        }

        /**
         * Refuses what {@code file} declares, as {@link #declare} would, without changing anything: a dry run.
         * What needs I/O to know -- opening a dimension table -- is left to the real change.
         */
        default void check(DeclarationFile file) {}
    }

    /**
     * A file not applied, and why.
     *
     * @param users the queries holding the object, for a change or removal refused because of them
     */
    public record Problem(
            DeclarationKind kind, String name, Path file, String code, String message, List<String> users) {}

    private final Map<DeclarationKind, ConfigFolderProperties.Folder> folders;
    private final Function<DeclarationKind, Set<String>> inline;
    private final PlaceholdersResolver placeholders;
    private final Target target;
    private final AuditSink audit;
    private final Map<DeclarationKind, Map<String, DeclarationFile>> applied = new EnumMap<>(DeclarationKind.class);
    private final Map<Path, Problem> problems = new TreeMap<>();
    private volatile @Nullable Instant lastLook;

    DeclarationWatcher(
            Map<DeclarationKind, ConfigFolderProperties.Folder> folders,
            Function<DeclarationKind, Set<String>> inline,
            PlaceholdersResolver placeholders,
            Map<DeclarationKind, Map<String, DeclarationFile>> loadedAtStart,
            Target target,
            AuditSink audit) {
        this.folders = folders;
        this.inline = inline;
        this.placeholders = placeholders;
        this.target = target;
        this.audit = audit;
        for (DeclarationKind kind : DeclarationKind.values()) {
            applied.put(kind, new LinkedHashMap<>(loadedAtStart.getOrDefault(kind, Map.of())));
        }
    }

    /** One look at every folder. Never throws: whatever goes wrong is a {@link Problem}. */
    public synchronized void poll() {
        for (DeclarationKind kind : DeclarationKind.values()) {
            try {
                look(kind);
            } catch (RuntimeException e) {
                LOG.warn("{} folder: one look failed; it looks again at the next interval", kind.label(), e);
            }
        }
        lastLook = Instant.now();
    }

    /** When the folders were last looked at, if they have been since start. */
    public Optional<Instant> lastLook() {
        return Optional.ofNullable(lastLook);
    }

    /** The file an object came from, if a file declared it. */
    public synchronized Optional<Path> fileOf(DeclarationKind kind, String name) {
        return Optional.ofNullable(current(kind).get(name)).map(DeclarationFile::path);
    }

    /** Every file not applied as written, in file order. */
    public synchronized List<Problem> problems() {
        return List.copyOf(problems.values());
    }

    /** The files in force, of one kind, by name. */
    public synchronized Map<String, DeclarationFile> files(DeclarationKind kind) {
        return Map.copyOf(current(kind));
    }

    /** The folder files of {@code kind} are read from and written to. */
    public ConfigFolderProperties.Folder folder(DeclarationKind kind) {
        return java.util.Objects.requireNonNull(folders.get(kind), "a folder per kind");
    }

    /** Whether {@code name} is declared inline in application.yaml, or on the node by the API. */
    public boolean declaredOtherwise(DeclarationKind kind, String name) {
        return inline.apply(kind).contains(name) || target.declaredElsewhere(kind, name);
    }

    /** What uses the object now; see {@link Target#users}. */
    public List<String> users(DeclarationKind kind, String name) {
        return target.users(kind, name);
    }

    /** Checks what {@code file} declares without changing anything; see {@link Target#check}. */
    public void check(DeclarationFile file) {
        target.check(file);
    }

    /**
     * Runs {@code work} with no look at the folders in between: a write puts a file in place, applies it and
     * -- when it is refused -- puts the old one back, and a poll must see the before or the after, never both.
     */
    public synchronized <T> T exclusively(java.util.function.Supplier<T> work) {
        return work.get();
    }

    /**
     * Applies the file for one name now, as the next look would: declares, replaces, switches on or off, or
     * removes the object when its file is gone. A write calls this straight after writing: the next poll
     * would find the file already applied, so without it the change would wait for nothing.
     *
     * @return why it was not applied, or null when it was (or there was nothing to apply)
     */
    public synchronized @Nullable PravahaException applyNow(DeclarationKind kind, String name) {
        ConfigFolderProperties.Folder folder = folder(kind);
        Map<String, DeclarationFile> current = current(kind);
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(folder.path())) {
            for (Path file : DeclarationFile.list(folder.path())) {
                if (DeclarationFile.nameOf(file).equals(name)) {
                    files.add(file);
                }
            }
        }
        if (files.isEmpty()) {
            return current.containsKey(name) ? removed(kind, name, current) : null;
        }
        if (files.size() > 1) {
            PravahaException twice = new PravahaException(
                    ConfigErrors.DECLARED_TWICE,
                    "'" + name + "' is declared by " + files + "; one file per " + kind.label() + ".");
            report(problem(kind, name, files.get(0), twice, List.of()));
            return twice;
        }
        return arrived(kind, name, files.get(0), current);
    }

    /** The names declared by files, of one kind. */
    public synchronized Set<String> declared(DeclarationKind kind) {
        return Set.copyOf(current(kind).keySet());
    }

    private Map<String, DeclarationFile> current(DeclarationKind kind) {
        return applied.computeIfAbsent(kind, k -> new LinkedHashMap<>());
    }

    private void look(DeclarationKind kind) {
        ConfigFolderProperties.Folder folder = java.util.Objects.requireNonNull(folders.get(kind), "a folder per kind");
        Map<String, DeclarationFile> current = current(kind);
        if (!Files.isDirectory(folder.path())) {
            if (folder.configured() || !current.isEmpty()) {
                // A folder that vanishes -- an unmounted volume -- is not every file in it deleted.
                report(new Problem(
                        kind,
                        "*",
                        folder.path(),
                        ConfigErrors.FOLDER_MISSING.code(),
                        folder.path() + " (" + folder.setting() + ") is not there any more. What its files declared "
                                + "is kept until the folder is back; to remove an object, remove its file.",
                        List.of()));
            }
            return;
        }
        clear(folder.path());
        Map<String, List<Path>> byName = new LinkedHashMap<>();
        for (Path file : DeclarationFile.list(folder.path())) {
            byName.computeIfAbsent(DeclarationFile.nameOf(file), name -> new ArrayList<>())
                    .add(file);
        }
        Set<Path> present = new HashSet<>();
        byName.values().forEach(present::addAll);
        for (Map.Entry<String, List<Path>> entry : byName.entrySet()) {
            String name = entry.getKey();
            if (entry.getValue().size() > 1) {
                for (Path file : entry.getValue()) {
                    report(problem(
                            kind,
                            name,
                            file,
                            new PravahaException(
                                    ConfigErrors.DECLARED_TWICE,
                                    "'" + name + "' is declared by " + entry.getValue() + "; one file per "
                                            + kind.label() + ". Neither is applied until one is removed."),
                            List.of()));
                }
                continue;
            }
            arrived(kind, name, entry.getValue().get(0), current);
        }
        for (String name : new ArrayList<>(current.keySet())) {
            if (!byName.containsKey(name)) {
                removed(kind, name, current);
            }
        }
        // A problem about a file that is gone, and was not holding an object, has nothing left to say.
        problems.keySet()
                .removeIf(file -> file.startsWith(folder.path())
                        && !present.contains(file)
                        && current.values().stream()
                                .noneMatch(declared -> declared.path().equals(file)));
    }

    private @Nullable PravahaException arrived(
            DeclarationKind kind, String name, Path path, Map<String, DeclarationFile> current) {
        DeclarationFile file;
        try {
            file = DeclarationFile.read(kind, path, placeholders);
        } catch (PravahaException e) {
            report(problem(kind, name, path, e, List.of()));
            return e;
        }
        DeclarationFile before = current.get(name);
        if (before != null
                && before.canonical().equals(file.canonical())
                && before.path().equals(path)) {
            clear(path);
            if (before.enabled() != file.enabled()) {
                // Switched on or off and nothing else: never refused for being in use -- disabling a stream
                // a query reads is the point. The query keeps running and receives nothing until enabled.
                current.put(name, file);
                target.changed();
                String what = file.enabled() ? "enabled" : "disabled";
                LOG.info("{} '{}' {} by {}", kind.label(), name, what, path);
                record(kind, name, what, path, true, "");
            }
            return null;
        }
        if (before == null && (inline.apply(kind).contains(name) || target.declaredElsewhere(kind, name))) {
            report(problem(
                    kind,
                    name,
                    path,
                    new PravahaException(
                            ConfigErrors.DECLARED_TWICE,
                            path + " declares " + kind.label() + " '" + name + "', which is already declared "
                                    + (inline.apply(kind).contains(name)
                                            ? "inline under " + kind.inlineBlock() + "." + name
                                            : "on this node by the API")
                                    + ". One name, one declaration: remove one of the two."),
                    List.of()));
            return new PravahaException(
                    ConfigErrors.DECLARED_TWICE,
                    java.util.Objects.requireNonNull(problems.get(path)).message());
        }
        List<String> users;
        try {
            users = target.declare(file);
        } catch (PravahaException e) {
            report(problem(kind, name, path, e, List.of()));
            return e;
        } catch (RuntimeException e) {
            PravahaException wrapped = new PravahaException(ConfigErrors.FILE_INVALID, path + ": " + e.getMessage());
            report(problem(kind, name, path, wrapped, List.of()));
            return wrapped;
        }
        if (!users.isEmpty()) {
            Problem held = inUse(kind, name, path, "changed", users);
            report(held);
            return new PravahaException(ConfigErrors.FILE_IN_USE, held.message());
        }
        current.put(name, file);
        clear(path);
        target.changed();
        String what = before == null ? "declared" : "replaced";
        LOG.info("{} '{}' {} from {}", kind.label(), name, what, path);
        record(kind, name, what, path, true, "");
        return null;
    }

    private @Nullable PravahaException removed(
            DeclarationKind kind, String name, Map<String, DeclarationFile> current) {
        Path path = java.util.Objects.requireNonNull(current.get(name)).path();
        List<String> users;
        try {
            users = target.remove(kind, name);
        } catch (RuntimeException e) {
            PravahaException refused = new PravahaException(
                    ConfigErrors.FILE_INVALID,
                    path + " was removed and " + kind.label() + " '" + name + "' could not be: " + e.getMessage());
            report(problem(kind, name, path, refused, List.of()));
            return refused;
        }
        if (!users.isEmpty()) {
            Problem held = inUse(kind, name, path, "was removed", users);
            report(held);
            return new PravahaException(ConfigErrors.FILE_IN_USE, held.message());
        }
        current.remove(name);
        clear(path);
        target.changed();
        LOG.info("{} '{}' removed: {} was taken out", kind.label(), name, path);
        record(kind, name, "removed", path, true, "");
        return null;
    }

    private Problem inUse(DeclarationKind kind, String name, Path path, String what, List<String> users) {
        return new Problem(
                kind,
                name,
                path,
                ConfigErrors.FILE_IN_USE.code(),
                path + " " + what + ", not applied: " + kind.label() + " '" + name + "' is in use by "
                        + String.join(", ", users) + " and keeps its running definition. Drop "
                        + (users.size() == 1 ? "it" : "them") + " and the "
                        + (what.equals("changed") ? "change" : "removal")
                        + " is applied at the next look, or restart the node.",
                List.copyOf(users));
    }

    private static Problem problem(
            DeclarationKind kind, String name, Path path, PravahaException e, List<String> users) {
        ErrorCode code = e.errorCode();
        String message = e.getMessage();
        String prefix = code.code() + "  ";
        return new Problem(
                kind,
                name,
                path,
                code.code(),
                message.startsWith(prefix) ? message.substring(prefix.length()) : message,
                users);
    }

    private void report(Problem problem) {
        Problem before = problems.put(problem.file(), problem);
        if (before == null || !before.message().equals(problem.message())) {
            LOG.warn("{} {} -- not applied; the node keeps running as it was", problem.code(), problem.message());
            record(problem.kind(), problem.name(), "refused", problem.file(), false, problem.code());
        }
    }

    private void clear(Path path) {
        problems.remove(path);
    }

    private void record(DeclarationKind kind, String name, String what, Path path, boolean allowed, String code) {
        audit.record(new AuditEvent(
                Instant.now(),
                FOLDERS,
                "config." + kind.label() + "." + what,
                name,
                allowed,
                allowed ? what : code,
                Optional.of(path.toString())));
    }
}
