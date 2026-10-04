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
package com.ash.messaging.pravaha.pgwire;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Transaction control -- {@code BEGIN}, {@code COMMIT}, {@code ROLLBACK}, savepoints, {@code SET
 * TRANSACTION} -- as accepted no-ops with PostgreSQL's own protocol state (PGWIRE-TX-1).
 *
 * <h2>Why a no-op is honest here</h2>
 *
 * <p>This gateway reads continuously maintained views and writes nothing: there is no change for a
 * transaction to make atomic, nothing for {@code COMMIT} to make durable, and nothing for {@code
 * ROLLBACK} to undo. Refusing {@code BEGIN} -- which is what the planner did, as {@code PRV-2001 ...
 * near the keyword 'BEGIN'} -- broke every client that wraps its reads in a transaction by default:
 * psycopg outside autocommit, pgjdbc with {@code setAutoCommit(false)}, Npgsql's {@code
 * BeginTransaction}, and most ORMs. What such a client does depend on is the <em>protocol</em> half
 * of a transaction, and that is kept exactly: the {@code CommandComplete} tag, the status byte every
 * {@code ReadyForQuery} carries ({@code I} idle, {@code T} in a block, {@code E} failed), and the
 * failed-block rule -- after an error inside a block, every statement but {@code ROLLBACK}, {@code
 * COMMIT} (which then reports {@code ROLLBACK}) and {@code ROLLBACK TO SAVEPOINT} is refused with
 * {@code 25P02} until the block ends. pgjdbc's "the database returned ROLLBACK, so the transaction
 * cannot be committed" and psycopg's {@code InFailedSqlTransaction} are written against exactly that.
 *
 * <h2>Read consistency: READ COMMITTED, not a snapshot</h2>
 *
 * <p>Each statement in a block reads the views as they are when <em>that statement</em> runs --
 * PostgreSQL's {@code READ COMMITTED}. Two reads in one block may see different epochs of a view that
 * is being maintained between them. {@code ViewQuery} has no snapshot to pin a block to, and building
 * one for a gateway whose clients overwhelmingly send one read per transaction was not cheap enough to
 * be worth it. {@code REPEATABLE READ} and {@code SERIALIZABLE} are therefore accepted -- refusing
 * them would break Npgsql's and pgjdbc's isolation-level setters -- with a {@code NOTICE} saying they
 * run as {@code READ COMMITTED}, and {@code SHOW transaction_isolation} always answers {@code read
 * committed}, because that is the truth. {@code READ WRITE} is accepted and writes stay refused, as
 * outside a block.
 */
final class PgTransactionBlock {

    /** {@code ReadyForQuery}'s three transaction statuses. */
    static final char IDLE = 'I';

    static final char IN_BLOCK = 'T';

    static final char FAILED = 'E';

    /** One transaction-control statement, recognised; {@link #run} decides what it does in this state. */
    record Command(
            Kind kind,
            String tag,
            @Nullable String savepoint,
            boolean chain,
            @Nullable String isolation) {}

    enum Kind {
        BEGIN,
        COMMIT,
        ROLLBACK,
        SAVEPOINT,
        RELEASE,
        ROLLBACK_TO,
        SET_TRANSACTION,
        SET_SESSION_CHARACTERISTICS,
        SET_TRANSACTION_SNAPSHOT;

        /** The statements PostgreSQL still runs in a failed block: the ones that end it or rewind it. */
        boolean exitsAFailedBlock() {
            return this == COMMIT || this == ROLLBACK || this == ROLLBACK_TO;
        }
    }

    /** A {@code NoticeResponse} to send before the {@code CommandComplete}: severity, SQLSTATE, text. */
    record Notice(String severity, String sqlState, String message) {}

    /** What {@link #run} answers: the notices, then the tag. */
    record Outcome(List<Notice> notices, String tag) {}

    private static final String NAME = "(\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";

    private static final String MODE = "(?:ISOLATION\\s+LEVEL\\s+(?:SERIALIZABLE|REPEATABLE\\s+READ|READ\\s+COMMITTED"
            + "|READ\\s+UNCOMMITTED)|READ\\s+WRITE|READ\\s+ONLY|(?:NOT\\s+)?DEFERRABLE)";

    private static final String MODES = "(" + MODE + "(?:\\s*,\\s*" + MODE + "|\\s+" + MODE + ")*)";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    private static final Pattern BEGIN =
            Pattern.compile("^(BEGIN(?:\\s+(?:WORK|TRANSACTION))?|START\\s+TRANSACTION)(?:\\s+" + MODES + ")?$", FLAGS);

    private static final Pattern END = Pattern.compile(
            "^(COMMIT|END|ROLLBACK|ABORT)(?:\\s+(?:WORK|TRANSACTION))?(?:\\s+AND\\s+(NO\\s+)?CHAIN)?$", FLAGS);

    private static final Pattern ROLLBACK_TO =
            Pattern.compile("^ROLLBACK(?:\\s+(?:WORK|TRANSACTION))?\\s+TO\\s+(?:SAVEPOINT\\s+)?" + NAME + "$", FLAGS);

    private static final Pattern SAVEPOINT = Pattern.compile("^SAVEPOINT\\s+" + NAME + "$", FLAGS);

    private static final Pattern RELEASE = Pattern.compile("^RELEASE(?:\\s+SAVEPOINT)?\\s+" + NAME + "$", FLAGS);

    private static final Pattern SET_TRANSACTION = Pattern.compile("^SET\\s+TRANSACTION\\s+" + MODES + "$", FLAGS);

    private static final Pattern SET_CHARACTERISTICS =
            Pattern.compile("^SET\\s+SESSION\\s+CHARACTERISTICS\\s+AS\\s+TRANSACTION\\s+" + MODES + "$", FLAGS);

    private static final Pattern SET_SNAPSHOT = Pattern.compile("^SET\\s+TRANSACTION\\s+SNAPSHOT\\b.*$", FLAGS);

    private static final Pattern ISOLATION = Pattern.compile(
            "ISOLATION\\s+LEVEL\\s+(SERIALIZABLE|REPEATABLE\\s+READ|READ\\s+COMMITTED|READ\\s+UNCOMMITTED)", FLAGS);

    private char status = IDLE;

    /** Open savepoints, oldest first; a name may repeat, and the newest of a name is the one that counts. */
    private final List<String> savepoints = new ArrayList<>();

    /** The status the next {@code ReadyForQuery} carries. */
    char status() {
        return status;
    }

    /**
     * Records that a statement failed: inside a block, the block is now failed ({@code E}) until it
     * ends. Outside one, nothing -- the statement was its own implicit transaction and is over.
     */
    void failed() {
        if (status == IN_BLOCK) {
            status = FAILED;
        }
    }

    /**
     * Refuses any statement in a failed block, with {@code 25P02}. Called for every statement that is
     * not transaction control; {@link #run} applies the same rule to the ones that are.
     */
    void refuseIfFailed() {
        if (status == FAILED) {
            throw new PravahaException(
                    PgWireErrors.TRANSACTION_ABORTED,
                    "current transaction is aborted, commands ignored until end of transaction block. An earlier "
                            + "statement in this block failed; send ROLLBACK (or ROLLBACK TO a savepoint) to go on.");
        }
    }

    /** Refuses {@code what} inside a block, as PostgreSQL refuses {@code DISCARD ALL} there ({@code 25001}). */
    void refuseInsideBlock(String what) {
        refuseIfFailed();
        if (status == IN_BLOCK) {
            throw new PravahaException(
                    PgWireErrors.TRANSACTION_ACTIVE, what + " cannot run inside a transaction block");
        }
    }

    /** {@code statement} as transaction control, or empty if it is anything else. */
    static Optional<Command> recognise(String statement) {
        String s = statement.strip();
        Matcher m;
        m = BEGIN.matcher(s);
        if (m.matches()) {
            String tag = m.group(1).toUpperCase(Locale.ROOT).startsWith("START") ? "START TRANSACTION" : "BEGIN";
            return Optional.of(new Command(Kind.BEGIN, tag, null, false, isolationIn(m.group(2))));
        }
        m = END.matcher(s);
        if (m.matches()) {
            String verb = m.group(1).toUpperCase(Locale.ROOT);
            boolean chain = m.group(0).toUpperCase(Locale.ROOT).endsWith("CHAIN") && m.group(2) == null;
            boolean commit = verb.equals("COMMIT") || verb.equals("END");
            return Optional.of(new Command(
                    commit ? Kind.COMMIT : Kind.ROLLBACK, commit ? "COMMIT" : "ROLLBACK", null, chain, null));
        }
        m = ROLLBACK_TO.matcher(s);
        if (m.matches()) {
            return Optional.of(new Command(Kind.ROLLBACK_TO, "ROLLBACK", name(m.group(1)), false, null));
        }
        m = SAVEPOINT.matcher(s);
        if (m.matches()) {
            return Optional.of(new Command(Kind.SAVEPOINT, "SAVEPOINT", name(m.group(1)), false, null));
        }
        m = RELEASE.matcher(s);
        if (m.matches()) {
            return Optional.of(new Command(Kind.RELEASE, "RELEASE", name(m.group(1)), false, null));
        }
        if (SET_SNAPSHOT.matcher(s).matches()) {
            return Optional.of(new Command(Kind.SET_TRANSACTION_SNAPSHOT, "SET", null, false, null));
        }
        m = SET_TRANSACTION.matcher(s);
        if (m.matches()) {
            return Optional.of(new Command(Kind.SET_TRANSACTION, "SET", null, false, isolationIn(m.group(1))));
        }
        m = SET_CHARACTERISTICS.matcher(s);
        if (m.matches()) {
            return Optional.of(
                    new Command(Kind.SET_SESSION_CHARACTERISTICS, "SET", null, false, isolationIn(m.group(1))));
        }
        return Optional.empty();
    }

    /**
     * Runs {@code command} against this session's transaction state.
     *
     * @throws PravahaException for what PostgreSQL refuses: anything but an exit in a failed block
     *     ({@code 25P02}), a savepoint verb outside a block ({@code 25P01}), a savepoint that does not
     *     exist ({@code 3B001}), or {@code SET TRANSACTION SNAPSHOT} ({@code 0A000}). The caller reports
     *     it and calls {@link #failed()}, which is what fails a block on a bad {@code RELEASE}.
     */
    Outcome run(Command command) {
        if (status == FAILED && !command.kind().exitsAFailedBlock()) {
            refuseIfFailed();
        }
        List<Notice> notices = new ArrayList<>();
        String tag = command.tag();
        switch (command.kind()) {
            case BEGIN -> {
                if (status == IN_BLOCK) {
                    notices.add(new Notice("WARNING", "25001", "there is already a transaction in progress"));
                } else {
                    open();
                }
            }
            case COMMIT, ROLLBACK -> {
                if (status == IDLE) {
                    if (command.chain()) {
                        throw noBlock(command.tag() + " AND CHAIN");
                    }
                    notices.add(new Notice("WARNING", "25P01", "there is no transaction in progress"));
                } else {
                    // COMMIT of a failed block is a rollback, and says so: the tag is how pgjdbc knows
                    // to throw rather than report a commit that did not happen.
                    tag = status == FAILED ? "ROLLBACK" : tag;
                    status = IDLE;
                    savepoints.clear();
                    if (command.chain()) {
                        open();
                    }
                }
            }
            case SAVEPOINT -> {
                requireBlock("SAVEPOINT");
                savepoints.add(command.savepoint());
            }
            case RELEASE -> {
                requireBlock("RELEASE SAVEPOINT");
                savepoints
                        .subList(
                                indexOf(java.util.Objects.requireNonNull(command.savepoint(), "RELEASE names one")),
                                savepoints.size())
                        .clear();
            }
            case ROLLBACK_TO -> {
                requireBlock("ROLLBACK TO SAVEPOINT");
                // The savepoint itself survives a rollback to it; everything after it does not.
                savepoints
                        .subList(
                                indexOf(java.util.Objects.requireNonNull(command.savepoint(), "ROLLBACK TO names one"))
                                        + 1,
                                savepoints.size())
                        .clear();
                status = IN_BLOCK;
            }
            case SET_TRANSACTION -> {
                if (status == IDLE) {
                    notices.add(
                            new Notice("WARNING", "25P01", "SET TRANSACTION can only be used in transaction blocks"));
                }
            }
            case SET_SESSION_CHARACTERISTICS -> {
                // Session defaults; accepted in or out of a block, as PostgreSQL accepts them.
            }
            case SET_TRANSACTION_SNAPSHOT ->
                throw new PravahaException(
                        PgWireErrors.UNSUPPORTED_SET,
                        "SET TRANSACTION SNAPSHOT imports another session's snapshot, and this gateway keeps no "
                                + "snapshots: every statement reads the views as they are when it runs (READ "
                                + "COMMITTED). Refused rather than accepted and ignored.");
        }
        String isolation = command.isolation();
        if (isolation != null && (isolation.startsWith("SERIALIZABLE") || isolation.startsWith("REPEATABLE"))) {
            notices.add(new Notice(
                    "NOTICE",
                    "00000",
                    "ISOLATION LEVEL " + isolation + " is accepted and runs as READ COMMITTED: this gateway reads "
                            + "continuously maintained views and takes no snapshot, so each statement in the block "
                            + "reads them as they are when that statement runs"));
        }
        return new Outcome(notices, tag);
    }

    /** {@link #run}, written to the wire: each notice, then {@code CommandComplete} with the tag. */
    void answer(PgBackend backend, Command command) throws java.io.IOException {
        Outcome outcome = run(command);
        for (Notice notice : outcome.notices()) {
            backend.noticeResponse(notice.severity(), notice.sqlState(), notice.message());
        }
        backend.commandComplete(outcome.tag());
    }

    private void open() {
        status = IN_BLOCK;
        savepoints.clear();
    }

    private void requireBlock(String verb) {
        if (status == IDLE) {
            throw noBlock(verb);
        }
    }

    private static PravahaException noBlock(String verb) {
        return new PravahaException(PgWireErrors.NO_TRANSACTION, verb + " can only be used in transaction blocks");
    }

    private int indexOf(String savepoint) {
        int at = savepoints.lastIndexOf(savepoint);
        if (at < 0) {
            throw new PravahaException(
                    PgWireErrors.NO_SUCH_SAVEPOINT, "savepoint \"" + savepoint + "\" does not exist");
        }
        return at;
    }

    /** PostgreSQL's identifier rule: a quoted name is exact, an unquoted one folds to lower case. */
    private static String name(String raw) {
        if (raw.startsWith("\"")) {
            return raw.substring(1, raw.length() - 1).replace("\"\"", "\"");
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    private static @Nullable String isolationIn(String modes) {
        if (modes == null) {
            return null;
        }
        Matcher m = ISOLATION.matcher(modes);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ") : null;
    }
}
