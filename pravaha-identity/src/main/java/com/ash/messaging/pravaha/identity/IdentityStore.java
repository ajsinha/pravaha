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
package com.ash.messaging.pravaha.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.common.io.SensitiveFiles;

/**
 * The identity store: an append-only journal of whole entities, replayed into memory at start
 * (ADR-052), with the registry journal's discipline -- length-prefixed records, owner-only before the
 * first byte, fsync'd before a change is acknowledged, and a torn final record (a crash mid-append)
 * dropped rather than refusing the file.
 *
 * <p>Not thread-safe; {@link IdentityService} serialises every call.
 */
final class IdentityStore {

    private final Path file;
    final Map<String, Identities.User> users = new LinkedHashMap<>();
    final Map<String, Identities.ApiKey> keys = new LinkedHashMap<>();
    final Map<String, Identities.Session> sessions = new LinkedHashMap<>();
    final Map<String, Identities.ResetToken> resets = new LinkedHashMap<>();

    /** An in-memory store, for an embedded engine or a test that wants no file. */
    static IdentityStore inMemory() {
        return new IdentityStore(null);
    }

    static IdentityStore open(Path file) {
        IdentityStore store = new IdentityStore(file);
        store.replay();
        return store;
    }

    private IdentityStore(Path file) {
        this.file = file;
    }

    void putUser(Identities.User u) {
        append(List.of(
                "user",
                u.username(),
                n(u.displayName()),
                n(u.email()),
                n(u.tenant()),
                String.join(",", u.roles()),
                u.status(),
                u.service() ? "1" : "0",
                n(u.passwordHash()),
                String.join(" ", u.previousHashes()),
                u.mustChangePassword() ? "1" : "0",
                t(u.passwordChangedAt()),
                Integer.toString(u.failedAttempts()),
                t(u.firstFailedAt()),
                t(u.lockedUntil()),
                t(u.lastLoginAt()),
                t(u.createdAt())));
        users.put(u.username(), u);
    }

    void putKey(Identities.ApiKey k) {
        append(List.of(
                "key",
                k.keyId(),
                n(k.name()),
                k.holder(),
                String.join(",", k.roles()),
                k.secretHash(),
                t(k.createdAt()),
                n(k.createdBy()),
                t(k.expiresAt()),
                t(k.revokedAt()),
                n(k.rotatedTo()),
                t(k.lastUsedAt())));
        keys.put(k.keyId(), k);
    }

    void putSession(Identities.Session s) {
        append(List.of("session", s.id(), s.tokenHash(), s.username(), t(s.createdAt()), t(s.absoluteExpiry())));
        sessions.put(s.id(), s);
    }

    void endSession(String id) {
        append(List.of("session-end", id));
        sessions.remove(id);
    }

    void putReset(Identities.ResetToken r) {
        append(List.of("reset", r.tokenHash(), r.username(), t(r.expiresAt()), n(r.issuedBy())));
        resets.put(r.tokenHash(), r);
    }

    void useReset(String tokenHash) {
        append(List.of("reset-used", tokenHash));
        resets.remove(tokenHash);
    }

    private void replay() {
        if (!Files.exists(file)) {
            return;
        }
        byte[] all;
        try {
            all = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the identity store at " + file, e);
        }
        ByteBuffer buffer = ByteBuffer.wrap(all);
        while (buffer.remaining() > 4) {
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
                break; // a torn final record: everything before it is intact
            }
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            apply(ControlWire.decode(bytes));
        }
    }

    private void apply(List<String> f) {
        switch (f.get(0)) {
            case "user" ->
                users.put(
                        f.get(1),
                        new Identities.User(
                                f.get(1),
                                v(f.get(2)),
                                v(f.get(3)),
                                v(f.get(4)),
                                set(f.get(5)),
                                f.get(6),
                                "1".equals(f.get(7)),
                                v(f.get(8)),
                                list(f.get(9)),
                                "1".equals(f.get(10)),
                                i(f.get(11)),
                                Integer.parseInt(f.get(12)),
                                i(f.get(13)),
                                i(f.get(14)),
                                i(f.get(15)),
                                i(f.get(16))));
            case "key" ->
                keys.put(
                        f.get(1),
                        new Identities.ApiKey(
                                f.get(1),
                                v(f.get(2)),
                                f.get(3),
                                set(f.get(4)),
                                f.get(5),
                                i(f.get(6)),
                                v(f.get(7)),
                                i(f.get(8)),
                                i(f.get(9)),
                                v(f.get(10)),
                                i(f.get(11))));
            case "session" ->
                sessions.put(f.get(1), new Identities.Session(f.get(1), f.get(2), f.get(3), i(f.get(4)), i(f.get(5))));
            case "session-end" -> sessions.remove(f.get(1));
            case "reset" ->
                resets.put(f.get(1), new Identities.ResetToken(f.get(1), f.get(2), i(f.get(3)), v(f.get(4))));
            case "reset-used" -> resets.remove(f.get(1));
            default ->
                throw new IllegalStateException("the identity store at " + file + " has a record of kind '" + f.get(0)
                        + "', which this engine does not know; it was written by a newer version");
        }
    }

    private void append(List<String> fields) {
        if (file == null) {
            return;
        }
        byte[] payload = ControlWire.encode(fields);
        ByteBuffer buffer = ByteBuffer.allocate(4 + payload.length)
                .putInt(payload.length)
                .put(payload)
                .flip();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            SensitiveFiles.createOwnerOnly(file);
            try (FileChannel channel = FileChannel.open(
                    file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // A change to who may sign in that is only in a page cache is worse than refusing it.
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "cannot append to the identity store at " + file
                            + "; the change is refused rather than acknowledged and lost at the next restart",
                    e);
        }
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static String v(String s) {
        return s.isEmpty() ? null : s;
    }

    private static String t(Instant at) {
        return at == null ? "" : at.toString();
    }

    private static Instant i(String s) {
        return s.isEmpty() ? null : Instant.parse(s);
    }

    private static Set<String> set(String csv) {
        return csv.isEmpty() ? Set.of() : new LinkedHashSet<>(Arrays.asList(csv.split(",")));
    }

    private static List<String> list(String spaced) {
        return spaced.isEmpty() ? List.of() : new ArrayList<>(Arrays.asList(spaced.split(" ")));
    }
}
