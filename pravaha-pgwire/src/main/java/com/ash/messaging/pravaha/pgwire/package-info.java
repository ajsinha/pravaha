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
 * <h2>What is still deliberately not implemented, after slice 2</h2>
 *
 * <p>Each of these is <em>refused with a message naming itself</em> rather than half-built. A
 * half-built extended query protocol is worse than none: a driver that negotiates Parse/Bind and
 * then gets nonsense fails somewhere unrelated, hours later.
 *
 * <ul>
 *   <li><strong>The extended query protocol</strong> ({@code Parse}, {@code Bind}, {@code Describe},
 *       {@code Execute}, {@code Close}, {@code Flush}, {@code Sync}). Refused with {@link
 *       com.ash.messaging.pravaha.pgwire.PgWireErrors#UNSUPPORTED_REQUEST}. Slice 1 called this the
 *       one that matters most for slice 2; slice 2 spent itself on the catalog and {@code SET}
 *       instead, because a driver that cannot list tables never gets far enough to prepare a
 *       statement, and starting the extended protocol before that was solid would have been exactly
 *       the half-built thing this list exists to avoid. {@code ViewQuery.prepare} still exists and
 *       still backs the Flight gateway's prepared statements, so this remains wiring rather than a
 *       missing capability -- for whichever slice takes it next.
 *   <li><strong>Prepared statements and portals.</strong> Same refusal, same reason -- they are the
 *       extended protocol's nouns.
 *   <li><strong>Cursors</strong> ({@code DECLARE} / {@code FETCH}). A result is materialised whole
 *       by {@code ViewQuery} and bounded by {@code ViewQuery.MAX_RESULT_ROWS}; a cursor would
 *       promise streaming that the layer underneath does not do.
 *   <li><strong>{@code COPY}</strong>, in either direction. Refused as unsupported.
 *   <li><strong>SSL/TLS.</strong> An {@code SSLRequest} is <em>declined</em> with a single {@code
 *       'N'}, which is the protocol's own way of saying "not offered" and makes a client fall back
 *       to plaintext rather than hang. <strong>Consequence worth stating plainly: with password
 *       authentication configured, the credential crosses the wire in the clear.</strong> Run this
 *       on a loopback interface or behind a TLS terminator until a later slice adds it -- slice 2
 *       did not touch this.
 *   <li><strong>{@code CancelRequest}.</strong> {@code BackendKeyData} is sent because clients
 *       expect it, and a cancel arriving on a second connection is read and ignored. A query is
 *       bounded by the read deadline instead.
 *   <li><strong>Anything that writes.</strong> Not refused here at all -- it is refused by the
 *       planner, one layer down, so that pgwire and Flight give the same answer.
 * </ul>
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
 * <p><strong>The extended query protocol is still not implemented</strong>, and staying refused is
 * the deliberate choice this slice made rather than an oversight: the catalog shim and {@code SET}
 * are what stood between this gateway and "a real client connects and lists tables" (Gate P6's
 * actual ask), and starting Parse/Bind/Describe/Execute/Sync before both of those were solid would
 * have spent the slice on the wrong thing. {@code psql} and a JDBC driver told {@code
 * preferQueryMode=simple} both run everything in this slice -- connection, catalog browsing, and
 * ordinary reads -- over the simple protocol alone.
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
