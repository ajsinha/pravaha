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
 * <h2>What is deliberately not implemented in slice 1</h2>
 *
 * <p>Each of these is <em>refused with a message naming itself</em> rather than half-built. A
 * half-built extended query protocol is worse than none: a driver that negotiates Parse/Bind and
 * then gets nonsense fails somewhere unrelated, hours later.
 *
 * <ul>
 *   <li><strong>The extended query protocol</strong> ({@code Parse}, {@code Bind}, {@code Describe},
 *       {@code Execute}, {@code Close}, {@code Flush}, {@code Sync}). Refused with {@link
 *       com.ash.messaging.pravaha.pgwire.PgWireErrors#UNSUPPORTED_REQUEST}. This is the one that
 *       matters most for slice 2: JDBC and psycopg both prefer it, and {@code ViewQuery.prepare}
 *       already exists to back it.
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
 *       on a loopback interface or behind a TLS terminator until slice 2 adds it.
 *   <li><strong>{@code CancelRequest}.</strong> {@code BackendKeyData} is sent because clients
 *       expect it, and a cancel arriving on a second connection is read and ignored. A query is
 *       bounded by the read deadline instead.
 *   <li><strong>Anything that writes.</strong> Not refused here at all -- it is refused by the
 *       planner, one layer down, so that pgwire and Flight give the same answer.
 * </ul>
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
