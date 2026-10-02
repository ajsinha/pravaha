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

/**
 * The PostgreSQL wire protocol, read half only.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Pravaha already answers SQL over Arrow Flight SQL, which is the right format for columnar
 * throughput and the wrong one for adoption: almost nothing a person already has installed speaks
 * it. The PostgreSQL v3 protocol is the opposite trade -- row-oriented and text-y, and understood
 * by {@code psql}, DBeaver, Grafana, every ORM and every dbt project without anybody installing
 * anything. This module is the second door onto the same rooms, not a second set of rooms.
 *
 * <h2>Why only the read half</h2>
 *
 * <p>Because that is the whole of what Pravaha has. A view is a table, {@code SELECT} over one
 * already works through {@link com.ash.messaging.pravaha.serving.ViewQuery}, and {@code INSERT},
 * {@code UPDATE} and {@code DELETE} are already refused by the planner. There is no write path to
 * expose, so there is none here, and a client that tries gets the planner's own refusal rather than
 * a second opinion written in this package.
 *
 * <h2>What is still deliberately not implemented, after slice 4</h2>
 *
 * <p>Each of these is <em>refused with a message naming itself</em> rather than half-built.
 *
 * <ul>
 *   <li><strong>Cursors</strong> ({@code DECLARE} / {@code FETCH}, the SQL statements -- not to be
 *       confused with {@code Execute}'s row limit, which {@link
 *       com.ash.messaging.pravaha.pgwire.PgPortal} answers honestly over an already-materialised
 *       result rather than a real cursor; see that class's own note). A result is materialised whole
 *       by {@code ViewQuery} and bounded by {@code ViewQuery.MAX_RESULT_ROWS}; {@code DECLARE} would
 *       promise streaming that the layer underneath does not do.
 *   <li><strong>{@code COPY}</strong>, in either direction, and {@code LISTEN}/{@code NOTIFY} and
 *       {@code SELECT STREAM}: refused by name, {@code PRV-6201} {@code 0A000}, as cursors are
 *       ({@link com.ash.messaging.pravaha.pgwire.PgWireErrors#refuseUnsupportedStatement}).
 *       A {@code SELECT} with no {@code FROM} -- the probe a connection pool validates with -- is
 *       answered, not refused: see {@link com.ash.messaging.pravaha.pgwire.PgConstantSelect}.
 *   <li><strong>{@code CancelRequest}.</strong> {@code BackendKeyData} is sent because clients
 *       expect it, and a cancel arriving on a second connection is read and ignored. A query is
 *       bounded by the read deadline instead.
 *   <li><strong>Binary parameters beyond the primitives.</strong> A binary {@code Bind} parameter
 *       is decoded for the fixed-width types and text only ({@link
 *       com.ash.messaging.pravaha.pgwire.PgTypes#decodeParameter}); any other is refused with {@link
 *       com.ash.messaging.pravaha.pgwire.PgWireErrors#UNSUPPORTED_WIRE_FORMAT}. Binary
 *       <em>results</em> are served for every type the gateway sends ({@link
 *       com.ash.messaging.pravaha.pgwire.PgTypes#encodeBinary}), because Npgsql -- the driver inside
 *       Power BI -- asks for them on every query.
 *   <li><strong>Anything that writes.</strong> Not refused here at all -- it is refused by the
 *       planner, one layer down, so that pgwire and Flight give the same answer. Inside a
 *       transaction block the refusal fails the block, as any error does.
 * </ul>
 *
 * <p><strong>Transactions are accepted, as no-ops with PostgreSQL's protocol state</strong>
 * (PGWIRE-TX-1): {@code BEGIN}/{@code START TRANSACTION}, {@code COMMIT}/{@code END}, {@code
 * ROLLBACK}/{@code ABORT}, savepoints and {@code SET TRANSACTION}, with the right tags and the {@code
 * I}/{@code T}/{@code E} status on every {@code ReadyForQuery}, on both protocols. There is nothing to
 * commit on a read-only gateway; what a driver needs is the state. Reads in a block are {@code READ
 * COMMITTED} -- each statement sees the views as they are when it runs, not a snapshot taken at
 * {@code BEGIN}. See {@link com.ash.messaging.pravaha.pgwire.PgTransactionBlock}, and {@link
 * com.ash.messaging.pravaha.pgwire.PgShow} for the settings drivers probe with {@code SHOW}.
 *
 * <h2>Slice 2: a catalog, and a session that connects</h2>
 *
 * <p>Slice 1 answered {@code SELECT} correctly and failed every tool that asks what tables exist
 * before it shows them, which is nearly every tool -- {@code psql}'s own {@code \d}, DBeaver,
 * Grafana. Two things fixed that, and a third was deliberately left for a later slice:
 *
 * <ul>
 *   <li><strong>{@link com.ash.messaging.pravaha.pgwire.PgCatalogShim}</strong>, a minimal read-only
 *       {@code pg_catalog}: {@code pg_class}, {@code pg_namespace}, {@code pg_attribute} and a
 *       handful of scalar functions ({@code version()}, {@code current_schema()}), computed from
 *       {@code ViewCatalog} on every query rather than cached, so it cannot drift from what {@code
 *       ViewQuery} actually serves. It recognises the specific query shapes {@code psql} (at the
 *       server version this gateway announces -- see {@code PravahaPgWireServer.SERVER_VERSION} for
 *       why that version is 9.4.26 and not the newest) and pgjdbc's {@code DatabaseMetaData} send,
 *       and refuses anything else in the family by name; it does not parse or evaluate general SQL
 *       against a virtual catalog. Filtered by {@link com.ash.messaging.pravaha.security.SecurityPolicy}
 *       exactly like every other read -- SX-5's lesson, that a catalog listing is a read and must not
 *       tell a denied principal what it would not tell them through {@code SELECT}, applies here by
 *       construction rather than by a second check.
 *   <li><strong>{@code SET}</strong>, handled by {@link com.ash.messaging.pravaha.pgwire.PgSessionSet}:
 *       a small, named allow-list of parameters this server accepts and ignores -- {@code
 *       extra_float_digits}, {@code application_name}, {@code client_min_messages}, and {@code
 *       client_encoding}/{@code DateStyle} only for the one value each already matches what this
 *       server does. Every entry exists because accepting it and doing nothing is provably the same
 *       as honouring it; everything else is refused by name rather than silently accepted, because a
 *       {@code SET} that appears to succeed and changes nothing real is the specific half-measure
 *       this codebase treats as worse than a refusal. Without this, a JDBC connection could not open
 *       at all: pgjdbc sends {@code SET extra_float_digits = 3} before it sends anything else.
 * </ul>
 *
 * <p>Slice 2 itself left the extended query protocol refused, deliberately: the catalog shim and
 * {@code SET} are what stood between this gateway and "a real client connects and lists tables"
 * (Gate P6's actual ask), and starting Parse/Bind/Describe/Execute/Sync before both of those were
 * solid would have spent the slice on the wrong thing. {@code psql} and a JDBC driver told {@code
 * preferQueryMode=simple} ran everything through slice 2 -- connection, catalog browsing, and
 * ordinary reads -- over the simple protocol alone. Slice 4 is what removes that qualifier; see
 * below.
 *
 * <h2>Slice 3: TLS, so the gateway can stop being off by default</h2>
 *
 * <p>{@code pravaha.pgwire.enabled} defaults to {@code false}, and the reason written into {@code
 * PravahaNode} and {@code application.yaml} was exact: this gateway had no TLS, so a configured
 * password crossed the wire in the clear, and a server that ships that by default is not one this
 * codebase is willing to turn on for anybody. The extended query protocol was never what stood
 * between this gateway and being deployable outside loopback -- pgjdbc already works today with
 * {@code preferQueryMode=simple} -- TLS was.
 *
 * <p>{@link com.ash.messaging.pravaha.pgwire.PgTls} is what closes it: a PEM certificate chain and
 * an unencrypted PKCS#8 private key, loaded and validated by {@link
 * com.ash.messaging.pravaha.pgwire.PravahaPgWireServer#encryptedWith}, and layered onto a plaintext
 * socket in place exactly where the protocol expects it -- an {@code SSLRequest} answered {@code
 * 'S'} instead of {@code 'N'}, with the upgrade happening in {@code PgWireConnection} before
 * authentication runs, so a cleartext {@code PasswordMessage} never leaves this process
 * unencrypted. Built the same way {@code PravahaFlightServer.encryptedWith} was, and mirroring its
 * hard-won lesson deliberately: CFG-6(b) found a private key configured without a certificate read
 * into a field and silently never used, serving plaintext while the node's own settings suggested
 * otherwise, and {@code PgTls.load} refuses that same half-configured pair loudly, before either
 * file is even opened, rather than repeat it.
 *
 * <p>A server with no certificate configured behaves exactly as slice 1 and slice 2 left it:
 * {@code 'N'}, plaintext, unchanged. This is additive, not a replacement of the old behaviour --
 * see {@code PsqlSessionTest} and {@code JdbcClientTest} for proof that both paths still work on
 * the same, TLS-configured server.
 *
 * <h2>Slice 4: the extended query protocol, so {@code preferQueryMode=simple} stops being needed</h2>
 *
 * <p>{@code Parse}, {@code Bind}, {@code Describe}, {@code Execute}, {@code Close}, {@code Flush}
 * and {@code Sync} are implemented -- see {@link
 * com.ash.messaging.pravaha.pgwire.PgExtendedSession}, which owns the session state ({@code
 * PgWireConnection} carries none between messages otherwise: named statements and portals that
 * outlive one message) this needed and did not have before. {@code ViewQuery.prepare} was, exactly
 * as slice 2 anticipated, wiring rather than new capability: it already split planning a statement
 * once from executing it many times with different bound values, which is the same split as {@code
 * Parse} versus {@code Bind}/{@code Execute}.
 *
 * <p>Two things this protocol needed that were genuinely new, not wiring:
 *
 * <ul>
 *   <li><strong>{@code $1}, {@code $2}, ...</strong> PostgreSQL's own placeholder syntax, which
 *       {@code SqlPlanner} has never seen -- Pravaha's dialect uses a positional {@code ?}
 *       (ADR-032). {@link com.ash.messaging.pravaha.pgwire.PgParameterSyntax} rewrites one into the
 *       other as text, before the SQL ever reaches the planner, and refuses -- naming {@link
 *       com.ash.messaging.pravaha.pgwire.PgWireErrors#UNSUPPORTED_PARAMETER_SYNTAX} -- the one shape
 *       that rewrite cannot mean the same thing twice: a {@code $n} reused, or out of order. A
 *       parameter in a position ADR-032 does not allow is refused with the code that already exists
 *       for it, {@code SQL_PARAMETER_NOT_A_VALUE} (PRV-2063), not a new pgwire-specific one beside
 *       it -- and the same is true of arity and type mismatches, {@code SQL_PARAMETER_ARITY} and
 *       {@code SQL_PARAMETER_TYPE} (PRV-2061/2062), both reused rather than duplicated.
 *   <li><strong>Error recovery.</strong> After a failure inside an extended-query message sequence,
 *       a real backend discards every message up to the client's own {@code Sync} rather than
 *       answering or re-refusing them, because the client's own recovery path expects exactly that
 *       and a driver that does not get it hangs rather than fails -- worse than either. {@code
 *       PgWireConnection.serve} owns this (it is a property of the whole sequence, not of one
 *       message); {@code PgExtendedSession} only reports that it happened.
 * </ul>
 *
 * <p>{@link com.ash.messaging.pravaha.pgwire.PgCatalogShim}'s queries run through the extended
 * protocol too, not only the simple one: a driver's own {@code DatabaseMetaData} calls are prepared
 * statements like any other once {@code preferQueryMode=simple} is not forced, and a real one binds
 * its table/column name filter as an actual parameter rather than inlining it as literal text the
 * way it does under the simple protocol -- which is why the catalog shim's own statements carry
 * placeholders too, substituted back into the matched text at {@code Bind}/{@code Execute} time
 * (see {@link com.ash.messaging.pravaha.pgwire.PgParameterSyntax#substituteLiterals}) rather than
 * rewritten to {@code ?}, since {@code PgCatalogShim} is a text recognizer with no placeholder
 * syntax of its own to bind against.
 *
 * <p><strong>{@code preferQueryMode=simple} is no longer required</strong> for the shapes {@code
 * JdbcClientTest} drives without it: a plain connection, {@code getTables()}/{@code getColumns()},
 * and a {@code PreparedStatement} with a bound parameter. It remains true, and untested by this
 * slice, that this is one gateway's worth of protocol coverage rather than a claim about every
 * shape every driver or ORM might send -- see this module's own test suite for exactly what was
 * driven, through a real driver, and what was not.
 *
 * <h2>Where authorization lives</h2>
 *
 * <p>Not here. This package authenticates a connection into a {@link
 * com.ash.messaging.pravaha.security.Principal} and hands that principal to {@code
 * ViewQuery.execute(sql, principal)} -- the same call the Flight producer makes, with the same
 * policy and the same audit sink. A transport that carried its own idea of who may read what would
 * be a second, weaker path to the data, and the weaker one is the one that gets used.
 */
package com.ash.messaging.pravaha.pgwire;
