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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * One PostgreSQL client connection, from its startup packet to its last message.
 *
 * <p>Thread per connection. That is the wrong shape for ten thousand idle subscribers and exactly
 * right for what this is: a handful of analysts and dashboards running one query at a time each, on
 * a server whose read concurrency is already bounded by {@code ReadAdmission} one layer down. An
 * event loop here would add a state machine to a state machine and bound nothing that is not
 * already bounded.
 *
 * <h2>The shape of a session</h2>
 *
 * <ol>
 *   <li>Startup, possibly preceded by an {@code SSLRequest} -- declined with {@code 'N'} if this
 *       server has no certificate configured, exactly as before; accepted with {@code 'S'} and
 *       upgraded to TLS in place if it does. See {@link PgTls}.
 *   <li>Authentication -- cleartext password verified by the configured {@link TokenVerifier}, or
 *       {@code AuthenticationOk} when no verifier is configured at all.
 *   <li>{@code ParameterStatus} x n, {@code BackendKeyData}, {@code ReadyForQuery}.
 *   <li>{@code Query} / {@code Terminate}, until the client stops.
 * </ol>
 *
 * <p>Step 2 is the whole of what this class decides about access. <strong>What the principal may
 * then read is not decided here</strong>: every query goes through {@code ViewQuery.execute(sql,
 * principal)}, which is where the policy is enforced and the audit written, and which is the same
 * call the Flight producer makes. A check written in this file would be a second opinion, and two
 * opinions about who may read what is one opinion too many.
 */
final class PgWireConnection implements Runnable {

    /**
     * How long a client has to complete the handshake.
     *
     * <p>Applied to the handshake only and cleared afterwards. An unauthenticated peer that opens a
     * socket and says nothing would otherwise hold a thread for as long as it likes, which is a
     * denial of service that costs the attacker one packet. An <em>authenticated</em> session, by
     * contrast, is allowed to sit idle: that is what a psql window does all afternoon.
     */
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;

    /**
     * How many magic packets ({@code SSLRequest}, {@code GSSENCRequest}) precede a real startup.
     *
     * <p>A client may legitimately send one of each. A client that sends them forever is a loop,
     * and answering it forever is this server's half of that loop.
     */
    private static final int MAX_PRELUDE_PACKETS = 4;

    private static final SecureRandom CANCEL_KEYS = new SecureRandom();

    private final Socket socket;
    private final ViewQuery queries;
    private final PgCatalogShim catalog;
    private final TokenVerifier verifier;
    private final PgTls tls;
    private final String serverVersion;

    PgWireConnection(
            Socket socket,
            ViewQuery queries,
            PgCatalogShim catalog,
            TokenVerifier verifier,
            PgTls tls,
            String serverVersion) {
        this.socket = socket;
        this.queries = queries;
        this.catalog = catalog;
        this.verifier = verifier;
        this.tls = tls;
        this.serverVersion = serverVersion;
    }

    @Override
    public void run() {
        Socket active = socket;
        PgBackend backend = null;
        try {
            active.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            Prelude prelude = handshake(active);
            if (prelude == null) {
                return; // A CancelRequest, or a client that gave up mid-handshake.
            }
            // handshake() may have layered TLS onto the connection, in which case every one of
            // these is the encrypted socket and its streams, not the plaintext one `active` still
            // named on entry -- see PgTls.serverSocket and the loop below.
            active = prelude.socket();

            try (BufferedInputStream in = new BufferedInputStream(active.getInputStream());
                    BufferedOutputStream out = new BufferedOutputStream(active.getOutputStream())) {
                PgFrontend frontend = new PgFrontend(in);
                backend = new PgBackend(out);
                Principal principal = authenticate(prelude.startup(), frontend, backend);
                if (principal == null) {
                    return; // Refused; the client has been told and the socket is closing.
                }
                active.setSoTimeout(0);
                ready(backend, principal, prelude.startup().parameters());
                serve(frontend, backend, principal);
            }
        } catch (PravahaException refused) {
            // A protocol-level failure: the connection does not survive it, because after a
            // framing error this server no longer knows where the next message begins.
            fatal(backend, refused);
        } catch (SocketTimeoutException slow) {
            // The handshake deadline. Nothing is sent: a peer that has not spoken has not
            // necessarily got as far as being a PostgreSQL client, and an ErrorResponse to
            // something that is not one is noise on a port scan.
        } catch (IOException disconnected) {
            // The client went away, or -- on a TLS-configured server -- opened an SSLRequest and
            // then failed the handshake that followed (this server asks for no client certificate,
            // so in practice this is a client that does not trust ours, or a port scanner that was
            // never TLS at all). Both look identical from here, and every one of these is a normal
            // end to a session: none is worth a stack trace in an operator's log.
        } finally {
            closeQuietly(active);
        }
    }

    // -------------------------------------------------------------------------------------
    // Handshake.

    /** What survives the prelude: the socket to serve on (upgraded or not) and the real startup. */
    private record Prelude(Socket socket, PgFrontend.Startup startup) {}

    /**
     * Reads past any {@code SSLRequest} to the real startup packet, or {@code null} to give up.
     *
     * <p>Builds its own {@link PgFrontend}/{@link PgBackend} directly over {@code initial}'s raw
     * streams, unbuffered -- a prelude packet is one small fixed-size read, buffering would only
     * cost a read-ahead this method would then have to account for at the one point that matters:
     * the moment {@code SSLRequest} is accepted and {@link PgTls#serverSocket} layers TLS onto the
     * same connection. Reading exactly as many bytes as asked and never more is what lets that
     * layering happen with nothing left over to replay.
     */
    private Prelude handshake(Socket initial) throws IOException {
        Socket active = initial;
        PgFrontend frontend = new PgFrontend(active.getInputStream());
        PgBackend backend = new PgBackend(active.getOutputStream());
        try {
            for (int prelude = 0; prelude <= MAX_PRELUDE_PACKETS; prelude++) {
                PgFrontend.Startup startup = frontend.readStartup();
                if (startup.isSslRequest()) {
                    if (tls == null) {
                        // Declined, not refused. See PgBackend.declineEncryption: 'N' is the
                        // protocol's own "not offered", and it is what makes a default `psql` fall
                        // back to plaintext and connect rather than fail with something about SSL.
                        // Exactly slice 1's behaviour, unchanged, for a server with no certificate
                        // configured.
                        backend.declineEncryption();
                        continue;
                    }
                    // Accepted: 'S' says so, and every byte from here on is TLS. The socket,
                    // frontend and backend all move to the encrypted layer together -- reading the
                    // real startup packet through the old, plaintext frontend after this point
                    // would read raw TLS handshake bytes as though they were PostgreSQL protocol.
                    backend.acceptEncryption();
                    active = tls.serverSocket(active);
                    active.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
                    frontend = new PgFrontend(active.getInputStream());
                    backend = new PgBackend(active.getOutputStream());
                    continue;
                }
                if (startup.isGssEncRequest()) {
                    // GSSAPI encryption is a different mechanism from TLS, and this server
                    // implements neither the negotiation nor the Kerberos machinery behind it --
                    // declined unconditionally, independent of whether a certificate is configured.
                    backend.declineEncryption();
                    continue;
                }
                if (startup.isCancelRequest()) {
                    // Slice 1 does not implement cancellation. The protocol's own rule is that a
                    // backend answers a CancelRequest with nothing at all -- no acknowledgement
                    // either way, because a reply would tell an unauthenticated peer whether it
                    // guessed a valid key. Closing silently is both correct and what a real backend
                    // does.
                    return null;
                }
                int major = startup.code() >>> 16;
                if (major != 3) {
                    throw new PravahaException(
                            PgWireErrors.UNSUPPORTED_PROTOCOL_VERSION,
                            "this server speaks PostgreSQL protocol 3.0; the client asked for " + major + "."
                                    + (startup.code() & 0xffff)
                                    + ". Protocol 2 clients predate PostgreSQL 7.4 and are not supported.");
                }
                return new Prelude(active, startup);
            }
            throw new PravahaException(
                    PgWireErrors.PROTOCOL_VIOLATION,
                    "the client sent more than " + MAX_PRELUDE_PACKETS
                            + " encryption requests without ever sending a startup packet");
        } catch (PravahaException refused) {
            // Reported here, against whichever backend was current at the moment of failure --
            // plaintext, or already upgraded to TLS -- rather than left to propagate to `run`,
            // which by this point in the connection's life has no backend of its own to report
            // through yet (authenticate/ready/serve have not started; `run`'s own PravahaException
            // handler exists for failures from those, after this method has already returned one).
            fatal(backend, refused);
            return null;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a socket that is already broken is the normal case, not an event.
        }
    }

    /**
     * Authenticates the connection, or refuses it.
     *
     * <h2>Why cleartext password, and not {@code AuthenticationOk} for everyone</h2>
     *
     * <p>Because {@code AuthenticationOk} unconditionally would be a second, weaker door onto the
     * same data: Flight refuses every call without a credential, and a pgwire port beside it that
     * refuses nothing makes the Flight check decorative. The owner's standing constraint is that
     * only authenticated users reach data, and it has to hold on whichever port a client picks.
     *
     * <p>Cleartext specifically, rather than MD5 or SCRAM, because of what {@link TokenVerifier}
     * is. Its contract is an <em>opaque bearer credential in, principal out</em> -- it verifies a
     * JWT against an issuer, or calls an introspection endpoint. MD5 and SCRAM both require the
     * server to hold either the password or a verifier derived from it, so that it can run the
     * challenge-response arithmetic. Pravaha deliberately holds neither, and pretending otherwise
     * would mean a second credential store beside the identity provider the deployment already has.
     * Cleartext is the only PostgreSQL authentication method whose shape is "the client sends the
     * secret and the server asks somebody else about it", which is exactly {@code TokenVerifier}.
     *
     * <p><strong>The cost, stated rather than buried: this cleartext {@code PasswordMessage} is
     * genuinely in the clear on a server with no certificate configured.</strong> That is
     * acceptable on loopback or behind a terminator and is not acceptable across a network. {@link
     * PravahaPgWireServer#encryptedWith} closes it -- authentication runs after the TLS handshake
     * in {@code PgWireConnection.run}, never before, so a configured certificate always covers the
     * password. The remaining exposure is a deployment that chose not to configure one, and that
     * remains an operator choice this server states rather than silently accepts.
     *
     * <p>A server built with no verifier at all sends {@code AuthenticationOk} and runs as {@link
     * Principal#ANONYMOUS}. That is the same deliberate two-state design {@code PrincipalMiddleware}
     * uses on the Flight side -- an embedded engine already behind its own wall -- rather than a
     * default that silently downgrades a configured one.
     *
     * @return the authenticated principal, or {@code null} if the connection was refused
     */
    private Principal authenticate(PgFrontend.Startup startup, PgFrontend frontend, PgBackend backend)
            throws IOException {
        if (verifier == null) {
            backend.authenticationOk();
            return Principal.ANONYMOUS;
        }
        backend.authenticationCleartextPassword();
        backend.flush();
        PgFrontend.Message message = frontend.readMessage();
        if (message == null) {
            return null; // The client hung up rather than answer.
        }
        if (message.type() != 'p') {
            throw new PravahaException(
                    PgWireErrors.PROTOCOL_VIOLATION,
                    "expected a PasswordMessage after the password request, got '" + message.type() + "'");
        }
        String password = message.asString();
        try {
            Principal principal = verifier.verify(password);
            if (principal == null || principal.isAnonymous()) {
                // A verifier that returns anonymous has failed to authenticate, whatever it meant
                // to do; treating that as success is how an audit log fills with rows attributed to
                // nobody. The same rule PrincipalMiddleware applies on the Flight side.
                throw new PravahaException(SecurityErrors.UNAUTHENTICATED, "the credential presented was not accepted");
            }
            backend.authenticationOk();
            return principal;
        } catch (PravahaException refused) {
            // 28P01 invalid_password specifically, rather than the generic mapping: it is what every
            // client's "wrong password, prompt again" path is written against. The message is the
            // verifier's own, which by TokenVerifier's contract says the credential was rejected and
            // nothing about why -- "expired" versus "unknown" is an oracle for whoever is guessing.
            backend.errorResponse("FATAL", "28P01", refused.getMessage(), null);
            return null;
        }
    }

    /** {@code ParameterStatus}, {@code BackendKeyData}, {@code ReadyForQuery}. */
    private void ready(PgBackend backend, Principal principal, Map<String, String> startupParameters)
            throws IOException {
        // What a client is entitled to assume about this server. Each of these is a promise the
        // encoder in PgTypes actually keeps -- in particular DateStyle, which is what makes
        // "2026-09-16 12:00:00+00" the right thing to send rather than a guess.
        backend.parameterStatus("server_version", serverVersion);
        backend.parameterStatus("server_encoding", "UTF8");
        backend.parameterStatus("client_encoding", "UTF8");
        backend.parameterStatus("DateStyle", "ISO, MDY");
        backend.parameterStatus("TimeZone", "UTC");
        backend.parameterStatus("integer_datetimes", "on");
        backend.parameterStatus("standard_conforming_strings", "on");
        backend.parameterStatus("application_name", startupParameters.getOrDefault("application_name", ""));
        backend.backendKeyData(
                ProcessHandle.current().pid() > Integer.MAX_VALUE
                        ? 0
                        : (int) ProcessHandle.current().pid(),
                CANCEL_KEYS.nextInt());

        String requested = startupParameters.get("user");
        // Only where a credential actually decided something. A server with no verifier runs every
        // session as ANONYMOUS by design, and telling `psql -U dana` that it is "connected as
        // 'anonymous', not 'dana'" is a warning about the configuration the operator chose --
        // printed at the top of every single session. A real psql found this on its first run.
        if (!principal.isAnonymous() && requested != null && !requested.equals(principal.id())) {
            // The credential decides who you are; the `user` field of a startup packet is whatever
            // the client typed. Saying so out loud costs nothing -- the person already holds the
            // credential, so this discloses nothing they do not have -- and it prevents the quiet
            // surprise of `psql -U alice` running an afternoon's queries as somebody else.
            backend.noticeResponse("connected as '" + principal.id() + "', not '" + requested
                    + "': the credential determines the principal, not the user name in the startup packet");
        }
        backend.readyForQuery(PgBackend.STATUS_IDLE);
    }

    // -------------------------------------------------------------------------------------
    // The message loop.

    private void serve(PgFrontend frontend, PgBackend backend, Principal principal) throws IOException {
        // One extended-query session per connection: Parse/Bind name statements and portals that
        // live until Close or the connection ends, so this state cannot be local to the message
        // loop the way everything before slice 4 was.
        PgExtendedSession extended = new PgExtendedSession(queries, catalog, principal);
        while (true) {
            PgFrontend.Message message;
            try {
                message = frontend.readMessage();
            } catch (PravahaException malformed) {
                fatal(backend, malformed);
                return;
            }
            if (message == null) {
                return; // Socket closed without a Terminate; the same end, less politely.
            }
            if (extended.inErrorRecovery() && message.type() != 'S' && message.type() != 'X') {
                // The protocol's own recovery rule: after an error inside an extended-query
                // sequence, every message is discarded until Sync, which is what stops a driver
                // hanging rather than failing. Silently dropped, not answered -- answering would be
                // one more message the client did not ask for on top of the one it is still waiting
                // to see fail.
                continue;
            }
            switch (message.type()) {
                case 'Q' -> simpleQuery(backend, principal, message.asString(), extended);
                case 'P' -> extended.parse(backend, message);
                case 'B' -> extended.bind(backend, message);
                case 'D' -> extended.describe(backend, message);
                case 'E' -> extended.execute(backend, message);
                case 'C' -> extended.close(backend, message);
                case 'H' ->
                    // Flush: push whatever is already written without ending the sequence. Every
                    // response this class writes goes through PgBackend's own buffered stream and is
                    // otherwise only guaranteed to reach the wire at the next ReadyForQuery.
                    backend.flush();
                case 'S' -> extended.sync(backend);
                case 'X' -> {
                    // Terminate. No reply: the protocol says the server closes, and a reply to a
                    // client that has already stopped reading is a write to a half-closed socket.
                    return;
                }
                default -> unimplemented(backend, message.type());
            }
        }
    }

    /**
     * The simple query protocol: {@code RowDescription}, {@code DataRow}*, {@code CommandComplete},
     * {@code ReadyForQuery}.
     *
     * <p>{@code ReadyForQuery} is sent on every path out of this method, including every failure.
     * A client that does not get one waits for ever, which is the difference between a query that
     * failed and a session that hung.
     */
    private void simpleQuery(PgBackend backend, Principal principal, String sql, PgExtendedSession extended)
            throws IOException {
        try {
            String statement = SimpleQueryText.singleStatement(sql);
            if (statement.isEmpty()) {
                backend.emptyQueryResponse();
                return;
            }
            if (PgSessionSet.isDiscardAll(statement)) {
                // Npgsql's pool resets every reused connection with this. Honoured, not ignored: the
                // only session state this server keeps is named statements and portals, and they go.
                extended.discardAll();
                backend.commandComplete("DISCARD ALL");
                return;
            }
            if (PgSessionSet.isSetStatement(statement)) {
                PgSessionSet.handle(statement);
                backend.commandComplete("SET");
                return;
            }
            PgWireErrors.refuseContinuousStatement(statement);
            // The catalog shim answers first, and only queries it recognises as pg_catalog
            // introspection (PgCatalogShim.looksLikeCatalogQuery) -- everything else, including
            // every ordinary SELECT over a view, falls through to the one call that matters below.
            // Same entry point as the Flight producer's `queries.execute(sql,
            // principalOf(context))`, so the policy that decides what this principal may read, the
            // row filter that gets ANDed into the plan, and the audit event that records the
            // decision are all the same ones -- not a pgwire copy of them.
            //
            // A trailing top-level LIMIT n (Power BI's DirectQuery sentinel) is taken off before the
            // view query and applied to its answer, and a `public.` schema qualifier is dropped: see
            // PgTrailingLimit and PgPublicSchema for why each is exact.
            ViewQuery.Result result = catalog.tryAnswer(statement, principal).orElseGet(() -> {
                java.util.Optional<PgTrailingLimit.Split> limited = PgTrailingLimit.split(statement);
                String unlimited = limited.map(PgTrailingLimit.Split::sql).orElse(statement);
                long limit = limited.map(PgTrailingLimit.Split::limit).orElse(PgTrailingLimit.NONE);
                return PgTrailingLimit.apply(queries.execute(PgPublicSchema.unqualify(unlimited), principal), limit);
            });

            // RowDescription first, and it is built whole before a byte is written: PgBackend
            // buffers a message body before framing it, so a column whose type this gateway refuses
            // throws here, with nothing yet sent, and the client gets a clean ErrorResponse instead
            // of a truncated result set.
            backend.rowDescription(result.schema());
            for (Object[] row : result.rows()) {
                backend.dataRow(row, result.schema());
            }
            backend.commandComplete("SELECT " + result.size());
        } catch (PravahaException e) {
            // The engine's own diagnosis, with its PRV code, rather than a generic internal error.
            // A client that gets "PRV-4023 ... this server serves [user_volume]" can act on it.
            backend.errorResponse(
                    "ERROR",
                    PgWireErrors.sqlStateFor(e),
                    e.getMessage(),
                    e.errorCode().name());
        } catch (RuntimeException e) {
            // XX000 internal_error, and the message rather than the class name: anything reaching
            // here is a bug in this server, and the person who has to find it is reading a psql
            // window, not a heap dump.
            backend.errorResponse("ERROR", "XX000", String.valueOf(e.getMessage()), null);
        } finally {
            backend.readyForQuery(PgBackend.STATUS_IDLE);
        }
    }

    /**
     * Refuses a message type this server does not implement at all, by name, and stays connected.
     *
     * <p>{@code ErrorResponse} followed by {@code ReadyForQuery} is precisely what a real backend
     * does when an extended-protocol sequence fails: the client's own recovery path expects it and
     * will resynchronise. Silence, or closing the socket, would leave a driver blocked on a {@code
     * Sync} reply that never comes -- and it would fail somewhere that names neither this server
     * nor the message it could not handle.
     *
     * <p>Parse/Bind/Describe/Execute/Close/Flush/Sync are no longer refused here -- see {@link
     * PgExtendedSession} and {@code serve}'s own dispatch. What remains is {@code FunctionCall}
     * (PostgreSQL's own, unrelated to a SQL function call), {@code COPY} in either direction, and a
     * stray {@code PasswordMessage} outside authentication.
     */
    private void unimplemented(PgBackend backend, char type) throws IOException {
        String what =
                switch (type) {
                    case 'F' -> "FunctionCall";
                    case 'c', 'd', 'f' -> "COPY";
                    case 'p' -> "PasswordMessage (outside authentication)";
                    default -> "message type '" + type + "'";
                };
        String detail = "Not implemented by this server. This gateway serves SELECT, over the simple or the "
                + "extended query protocol; see the pravaha-pgwire package documentation for the full list "
                + "of what is deliberately absent.";
        PravahaException refusal = new PravahaException(
                PgWireErrors.UNSUPPORTED_REQUEST, what + " is not supported by this server. " + detail);
        backend.errorResponse("ERROR", PgWireErrors.sqlStateFor(refusal), refusal.getMessage(), null);
        backend.readyForQuery(PgBackend.STATUS_IDLE);
    }

    /** A failure the connection cannot survive: say so, then let the socket close. */
    private void fatal(PgBackend backend, PravahaException e) {
        try {
            backend.errorResponse(
                    "FATAL",
                    PgWireErrors.sqlStateFor(e),
                    e.getMessage(),
                    e.errorCode().name());
        } catch (IOException gone) {
            // The peer that sent us something unframeable has stopped reading. Nothing to do and
            // nothing lost: the diagnosis was for them.
        }
    }
}
