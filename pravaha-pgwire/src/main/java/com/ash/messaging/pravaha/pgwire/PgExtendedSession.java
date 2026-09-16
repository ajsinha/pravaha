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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

/**
 * The extended query protocol: {@code Parse}, {@code Bind}, {@code Describe}, {@code Execute},
 * {@code Close}, and the session state ({@code PgWireConnection} is stateless between messages
 * otherwise) that makes named statements and portals possible at all.
 *
 * <h2>Why this was wiring rather than new capability</h2>
 *
 * <p>{@code ViewQuery.prepare} already plans and authorizes a statement once and lets it be executed
 * many times with different bound values -- that split, statement-versus-portal, is exactly what
 * {@code Parse} versus {@code Bind}/{@code Execute} needs. This class does not teach the engine
 * anything new; it gives four PostgreSQL message names to state {@code ViewQuery} already had a
 * shape for, plus the two things that are genuinely this protocol's own: naming ({@code Parse}
 * assigns a statement a name, {@code Bind} assigns a portal one) and chunked delivery ({@code
 * Execute}'s row limit, answered over an already-materialised {@link ViewQuery.Result} -- see {@link
 * PgPortal}'s own note on why that is honest about not being a real cursor).
 *
 * <h2>Statement classification, once, at {@code Parse}</h2>
 *
 * <p>{@code PgWireConnection.simpleQuery} already answers four shapes of statement text -- a view
 * query, a {@code pg_catalog} question, a {@code SET}, an empty string -- with four different calls.
 * {@link #compile} makes that same classification once, into a {@link PgStatement}, and every later
 * message ({@code Describe}, {@code Bind}, {@code Execute}) reads it off the statement rather than
 * re-deciding what kind of thing the SQL is.
 *
 * <h2>{@code $1}, {@code $2}, ...</h2>
 *
 * <p>PostgreSQL's own placeholder syntax, which {@code SqlPlanner} has never seen -- Pravaha's
 * dialect uses {@code ?} (ADR-032). {@link PgParameterSyntax#toQuestionMarks} rewrites one into the
 * other before a view query ever reaches {@code queries.prepare}; see that class for exactly when
 * the rewrite is safe and when it is refused rather than guessed at.
 *
 * <h2>Error recovery</h2>
 *
 * <p>Not handled here at all, deliberately: it is a property of the whole extended-query message
 * stream, not of one message, so {@code PgWireConnection.serve} owns it -- every message in this
 * class's dispatch is skipped once {@link #inErrorRecovery()} is true, until the client's own {@code
 * Sync} arrives and {@link #sync} clears it. What lives here is only that every method below reports
 * its own failure and asks to enter that state, rather than letting an exception escape uncaught
 * into a socket a client is still expecting {@code ReadyForQuery} on.
 */
final class PgExtendedSession {

    private final ViewQuery queries;
    private final PgCatalogShim catalog;
    private final Principal principal;

    private final Map<String, PgStatement> statements = new HashMap<>();
    private final Map<String, PgPortal> portals = new HashMap<>();

    private boolean errorState;

    PgExtendedSession(ViewQuery queries, PgCatalogShim catalog, Principal principal) {
        this.queries = queries;
        this.catalog = catalog;
        this.principal = principal;
    }

    /**
     * Whether an error inside this Sync-delimited sequence has already been reported. While this is
     * true, {@code PgWireConnection.serve} discards every message except {@code Sync} and {@code
     * Terminate} -- the protocol's own recovery rule, and the reason a driver that gets it fails
     * rather than hangs after an error mid-sequence.
     */
    boolean inErrorRecovery() {
        return errorState;
    }

    // -------------------------------------------------------------------------------------
    // Parse

    void parse(PgBackend backend, PgFrontend.Message message) throws IOException {
        try {
            PgFrontend.MessageReader r = message.reader();
            String name = r.cstring();
            String rawSql = r.cstring();
            short declaredParamCount = r.int16();
            // Read and discarded: a client's declared parameter OIDs are advisory in every real
            // backend too (zero means "infer"), and Pravaha's own planner is authoritative about
            // what a placeholder needs -- see PgTypes.decodeParameter's own note. Reading them is
            // still required to find the end of this message.
            for (int i = 0; i < declaredParamCount; i++) {
                r.int32();
            }
            statements.put(name, compile(rawSql));
            backend.parseComplete();
        } catch (PravahaException refused) {
            fail(backend, refused);
        }
    }

    private PgStatement compile(String rawSql) {
        String statement = SimpleQueryText.singleStatement(rawSql);
        if (statement.isEmpty()) {
            return new PgStatement.Empty();
        }
        if (PgSessionSet.isSetStatement(statement)) {
            // Validated now, not deferred to Execute: a real backend refuses a bad statement at
            // Parse, and doing the same work twice (Parse and Execute) is cheap for a SET, which
            // never touches the engine at all.
            PgSessionSet.handle(statement);
            return new PgStatement.ForSet(statement);
        }
        // Recognised by a bare probe first: PgCatalogShim matches this statement's own markers
        // (join shape, column names, function names) whether or not it has $n placeholders yet, so
        // whether it is catalog-shaped at all does not depend on knowing their count.
        if (catalog.tryAnswer(statement, principal).isPresent()) {
            int parameterCount = PgParameterSyntax.placeholderCount(statement);
            // The wildcard probe: PgCatalogShim's own filters are all "LIKE pattern", so a '%' in
            // every placeholder's place matches everything and is a valid answer to plan a schema
            // from, without yet knowing what a client will actually Bind.
            String wildcarded =
                    PgParameterSyntax.substituteLiterals(statement, java.util.Collections.nCopies(parameterCount, "%"));
            StreamSchema schema = catalog.tryAnswer(wildcarded, principal)
                    .orElseThrow(() -> new IllegalStateException(
                            "a catalog statement recognised with its placeholders present stopped being "
                                    + "one once they were replaced with '%'"))
                    .schema();
            return new PgStatement.ForCatalog(statement, parameterCount, schema);
        }
        String rewritten = PgParameterSyntax.toQuestionMarks(statement);
        return new PgStatement.ForView(queries.prepare(rewritten, principal));
    }

    // -------------------------------------------------------------------------------------
    // Bind

    void bind(PgBackend backend, PgFrontend.Message message) throws IOException {
        try {
            PgFrontend.MessageReader r = message.reader();
            String portalName = r.cstring();
            String statementName = r.cstring();
            PgStatement statement = requireStatement(statementName);

            short[] paramFormats = readFormatCodes(r);
            short paramCount = r.int16();
            byte[][] rawValues = new byte[paramCount][];
            for (int i = 0; i < paramCount; i++) {
                rawValues[i] = r.lengthPrefixedValueOrNull();
            }
            short[] resultFormats = readFormatCodes(r);
            for (int i = 0; i < resultFormats.length; i++) {
                requireTextFormat(resultFormats[i]);
            }

            BoundParameters parameters = bindParameters(statement, paramFormats, rawValues);
            portals.put(portalName, new PgPortal(statement, parameters));
            backend.bindComplete();
        } catch (PravahaException refused) {
            fail(backend, refused);
        }
    }

    /** Format codes: 0 sent means every value is text; 1 sent means that one code applies to all; else one each. */
    private static short[] readFormatCodes(PgFrontend.MessageReader r) {
        short declared = r.int16();
        short[] codes = new short[declared];
        for (int i = 0; i < declared; i++) {
            codes[i] = r.int16();
        }
        return codes;
    }

    private static short formatFor(short[] codes, int index) {
        if (codes.length == 0) {
            return PgBackend.FORMAT_TEXT;
        }
        return codes.length == 1 ? codes[0] : codes[index];
    }

    private static void requireTextFormat(short format) {
        if (format != PgBackend.FORMAT_TEXT) {
            throw new PravahaException(
                    PgWireErrors.UNSUPPORTED_WIRE_FORMAT,
                    "binary result format was requested; this gateway sends text only, in every "
                            + "RowDescription and every DataRow it has ever produced. Bind without "
                            + "requesting binary results.");
        }
    }

    private BoundParameters bindParameters(PgStatement statement, short[] paramFormats, byte[][] rawValues) {
        List<TypeName> types = statement.parameterTypes();
        if (rawValues.length != types.size()) {
            // Reuses BoundParameters' own arity refusal (PRV-2061) rather than writing a second
            // message that says the same thing: an empty array of the wrong length is enough to
            // make requireArity throw with the right counts, and nothing is decoded first.
            BoundParameters.of(new Object[rawValues.length]).requireArity(types.size());
        }
        Object[] values = new Object[rawValues.length];
        for (int i = 0; i < rawValues.length; i++) {
            short format = formatFor(paramFormats, i);
            values[i] = PgTypes.decodeParameter(types.get(i), format, rawValues[i]);
        }
        return BoundParameters.of(values);
    }

    // -------------------------------------------------------------------------------------
    // Describe

    void describe(PgBackend backend, PgFrontend.Message message) throws IOException {
        try {
            PgFrontend.MessageReader r = message.reader();
            char kind = r.char8();
            String name = r.cstring();
            PgStatement statement =
                    switch (kind) {
                        case 'S' -> requireStatement(name);
                        case 'P' -> requirePortal(name).statement();
                        default ->
                            throw new PravahaException(
                                    PgWireErrors.PROTOCOL_VIOLATION,
                                    "Describe named object kind '" + kind + "'; the protocol defines only "
                                            + "'S' (statement) and 'P' (portal)");
                    };
            if (kind == 'S') {
                backend.parameterDescription(statement.parameterTypes());
            }
            Optional<StreamSchema> schema = statement.resultSchema();
            if (schema.isPresent()) {
                backend.rowDescription(schema.get());
            } else {
                backend.noData();
            }
        } catch (PravahaException refused) {
            fail(backend, refused);
        }
    }

    // -------------------------------------------------------------------------------------
    // Execute

    void execute(PgBackend backend, PgFrontend.Message message) throws IOException {
        try {
            PgFrontend.MessageReader r = message.reader();
            String portalName = r.cstring();
            int maxRows = r.int32();
            PgPortal portal = requirePortal(portalName);

            if (portal.statement() instanceof PgStatement.Empty) {
                backend.emptyQueryResponse();
                return;
            }
            if (portal.statement() instanceof PgStatement.ForSet forSet) {
                PgSessionSet.handle(forSet.sql());
                backend.commandComplete("SET");
                return;
            }
            if (!portal.hasRun()) {
                portal.runOnce(runOnce(portal));
            }
            deliverRows(backend, portal, maxRows);
        } catch (PravahaException refused) {
            fail(backend, refused);
        }
    }

    private ViewQuery.Result runOnce(PgPortal portal) {
        if (portal.statement() instanceof PgStatement.ForCatalog forCatalog) {
            // The real bound values substituted back in, not the wildcard probe Parse used only to
            // learn the schema: a client must never read rows filtered by '%' when it asked to bind
            // its own value. Re-run rather than cached, too -- the catalogue is cheap enough to
            // recompute that "live, not cached" costs nothing worth trading away for it.
            List<String> values = new ArrayList<>(forCatalog.parameterCount());
            for (int i = 0; i < forCatalog.parameterCount(); i++) {
                values.add((String) portal.parameters().at(i));
            }
            String materialized = PgParameterSyntax.substituteLiterals(forCatalog.sql(), values);
            return catalog.tryAnswer(materialized, principal)
                    .orElseThrow(() -> new IllegalStateException(
                            "a catalog statement validated at Parse stopped being one by Execute"));
        }
        if (portal.statement() instanceof PgStatement.ForView forView) {
            return queries.execute(forView.prepared(), portal.parameters(), principal);
        }
        throw new IllegalStateException("ForSet and Empty are handled before runOnce is reached");
    }

    private void deliverRows(PgBackend backend, PgPortal portal, int maxRows) throws IOException {
        List<Object[]> rows = portal.result().rows();
        StreamSchema schema = portal.result().schema();
        int start = portal.cursor();
        int end = maxRows <= 0 ? rows.size() : Math.min(rows.size(), start + maxRows);
        for (int i = start; i < end; i++) {
            backend.dataRow(rows.get(i), schema);
        }
        portal.advanceTo(end);
        if (end < rows.size()) {
            // More rows remain: the client's own signal to Execute this portal again rather than
            // treat this batch as the whole answer.
            backend.portalSuspended();
        } else {
            backend.commandComplete("SELECT " + rows.size());
        }
    }

    // -------------------------------------------------------------------------------------
    // Close, Sync

    /**
     * Forgets a statement or portal by name. Never refused, even if the name was never {@code
     * Parse}d/{@code Bind} -- closing something that is not there is what a client does when it is
     * not sure whether it already closed it, and a real backend does not treat that as a mistake.
     */
    void close(PgBackend backend, PgFrontend.Message message) throws IOException {
        PgFrontend.MessageReader r = message.reader();
        char kind = r.char8();
        String name = r.cstring();
        if (kind == 'S') {
            statements.remove(name);
        } else if (kind == 'P') {
            portals.remove(name);
        }
        // An unrecognised kind byte is not refused either, for the same reason: Close never fails.
        backend.closeComplete();
    }

    /** Ends this Sync-delimited sequence: clears any error state, and answers {@code ReadyForQuery}. */
    void sync(PgBackend backend) throws IOException {
        errorState = false;
        backend.readyForQuery(PgBackend.STATUS_IDLE);
    }

    // -------------------------------------------------------------------------------------
    // Shared

    private PgStatement requireStatement(String name) {
        PgStatement statement = statements.get(name);
        if (statement == null) {
            throw new PravahaException(
                    PgWireErrors.UNKNOWN_STATEMENT,
                    (name.isEmpty() ? "the unnamed statement" : "statement '" + name + "'")
                            + " does not exist. It was never Parsed on this connection, or has been Closed.");
        }
        return statement;
    }

    private PgPortal requirePortal(String name) {
        PgPortal portal = portals.get(name);
        if (portal == null) {
            throw new PravahaException(
                    PgWireErrors.UNKNOWN_PORTAL,
                    (name.isEmpty() ? "the unnamed portal" : "portal '" + name + "'")
                            + " does not exist. It was never Bound on this connection, or has been Closed.");
        }
        return portal;
    }

    private void fail(PgBackend backend, PravahaException refused) throws IOException {
        errorState = true;
        backend.errorResponse(
                "ERROR",
                PgWireErrors.sqlStateFor(refused),
                refused.getMessage(),
                refused.errorCode().name());
    }
}
