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
package com.ash.messaging.pravaha.registry;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * Registry error codes, PRV-8nnn: the lifecycle of a registered query.
 *
 * <p>8000 and not 5000, which is where these started. 5xxx belongs to plugins, and
 * {@code REGISTRY_NAME_IN_USE} collided with {@code PLUGIN_MISSING_SETTING} -- two different
 * failures answering to one code, which makes a support conversation start with "which 5001?".
 * Renumbered while nothing had shipped against them; a released code is never renumbered.
 */
public final class RegistryErrors {

    /** A name is already taken by a different computation. */
    public static final ErrorCode NAME_IN_USE = new ErrorCode(8001, "REGISTRY_NAME_IN_USE");

    /**
     * A name cannot be used for a view, whatever else is true of it.
     *
     * <p>Distinct from {@link #NAME_IN_USE}, which it was first folded into. "already taken" and
     * "cannot be a name at all" call for different actions, and a code that means both tells the
     * reader neither.
     */
    public static final ErrorCode NAME_UNUSABLE = new ErrorCode(8008, "REGISTRY_NAME_UNUSABLE");

    /** No registered query answers to that name. */
    public static final ErrorCode NO_SUCH_QUERY = new ErrorCode(8002, "REGISTRY_NO_SUCH_QUERY");

    /** The operation is not legal from the state the query is in. */
    public static final ErrorCode ILLEGAL_TRANSITION = new ErrorCode(8003, "REGISTRY_ILLEGAL_TRANSITION");

    /** The query failed while running; its recorded cause says how. */
    public static final ErrorCode QUERY_FAILED = new ErrorCode(8004, "REGISTRY_QUERY_FAILED");

    /**
     * A record in the registry journal cannot be read.
     *
     * <p>Refused rather than skipped. A skipped registration is a view a client expects to find and
     * will not, and it would fail at subscribe time with "no such view" -- a long way from the
     * unreadable byte that caused it.
     */
    public static final ErrorCode JOURNAL_UNREADABLE = new ErrorCode(8005, "REGISTRY_JOURNAL_UNREADABLE");

    /**
     * The registry journal cannot be written.
     *
     * <p>The registration is refused. Acknowledging one that will not survive a restart tells the
     * client something that is not true, and nothing will correct it later.
     */
    public static final ErrorCode JOURNAL_UNWRITABLE = new ErrorCode(8006, "REGISTRY_JOURNAL_UNWRITABLE");

    /**
     * A journalled registration was replayed for a principal who may no longer have it.
     *
     * <p>A registration is not a standing permission. Replaying blindly would be a way to keep an
     * entitlement after it was revoked, by having registered before it was.
     */
    public static final ErrorCode REPLAY_UNAUTHORIZED = new ErrorCode(8007, "REGISTRY_REPLAY_UNAUTHORIZED");

    /**
     * A sink a registered query writes to refused a batch, and has been detached from it.
     *
     * <p>Recorded on the registration and logged rather than thrown into the query. Writing later
     * batches over the one that failed would leave the sink missing a change with nothing to say
     * so -- a retraction that never arrived is a total that is wrong for ever -- so the sink stops,
     * and the query, its view and any other sink on it carry on (ADR-043).
     */
    public static final ErrorCode SINK_WRITE_FAILED = new ErrorCode(8009, "REGISTRY_SINK_WRITE_FAILED");

    /**
     * A registration names a sink whose declared schema or key does not match the query's output or
     * view key. Refused before the sink is opened: a sink reads rows through its own schema, so a
     * mismatch writes plausible nonsense rather than failing, and a key mismatch sends retractions to
     * the wrong records.
     */
    public static final ErrorCode SINK_SHAPE_MISMATCH = new ErrorCode(8010, "REGISTRY_SINK_SHAPE_MISMATCH");

    // 8011..8017 are taken and are deliberately not filled in here: 8011-8016 belong to the
    // time-travel debugger and 8017 to the CONTINUOUS QUERY grammar, both of which merged ahead of
    // this. The two below were written as 8011 and 8012 and renumbered before either had shipped,
    // which is the only time a code may move -- ErrorCodeUniquenessTest would have caught the
    // collision, but after a merge, in somebody else's build.

    /**
     * A {@code WITH (...)} option on a registration that this engine does not build, or one whose
     * value is not the kind of thing it names.
     *
     * <p>Named rather than ignored, which is the whole reason a {@code WITH} list on a plain
     * {@code CREATE} was refused outright before B8: an option nobody reads is a setting the person
     * who wrote it believes is in force. See {@link RegistrationOptions} for the ones that exist; a
     * replacement's are {@link ReplacementOptions} and are refused here by name too, with the
     * statement that does take them.
     */
    public static final ErrorCode OPTION_UNKNOWN = new ErrorCode(8017, "REGISTRY_OPTION_UNKNOWN");

    // 8011..8016 are the time-travel debugger's and live in DebugErrors, next door; 8017 is above.
    // The two below were written as 8011 and 8012 against a tree that had neither, and renumbered
    // before either had shipped -- the only time a code may move. ErrorCodeUniquenessTest would
    // have caught the collision, but after a merge, in somebody else's build.

    /**
     * The name a subscription was opened under has been dropped, so there are no more changes to it.
     *
     * <p>STRM-12 and STRM-14. An administrative drop used to reach a Flight subscriber as
     * {@code listener.completed()} -- the same signal as the client's own {@code close()} and as a
     * graceful shutdown -- and to reach an in-process subscriber as nothing at all, because
     * {@code RegisteredQuery.close()} touched no subscription and left it in the sink's listener
     * list for ever. Where the computation survives under another name it was worse still: the
     * stream went on delivering rows under a name a read of the view refused as nonexistent, and
     * the policy went on being asked about a name it could no longer have an opinion on.
     *
     * <p>Distinct from {@link #NO_SUCH_QUERY}, which is a name that was never here. This one was,
     * and the subscriber's copy is right up to the moment it ended.
     */
    public static final ErrorCode QUERY_DROPPED = new ErrorCode(8018, "REGISTRY_QUERY_DROPPED");

    /**
     * The node is shutting down, so this subscription ends without the query having ended.
     *
     * <p>STRM-12's second half. A graceful shutdown drains in-flight Flight calls, so
     * {@code listener.completed()} fired on the way out and a client read "this stream is
     * finished" -- for a query that is journalled, comes back {@code RUNNING} after the restart,
     * and moves on without the subscriber that stopped. Retryable, and said as such, because the
     * right response is to reconnect.
     */
    public static final ErrorCode NODE_STOPPING = new ErrorCode(8019, "REGISTRY_NODE_STOPPING");

    /**
     * A registration its tenant has no query left for (ADR-050).
     *
     * <p>Raised at registration, before a sink is opened or a row is read, and never later: a
     * running query is not stopped because its tenant's quota was lowered. Every name counts,
     * including a name that attaches to a computation its tenant already runs, because a name is
     * what a tenant holds and what an operator sees listed against it.
     */
    public static final ErrorCode TENANT_QUERY_QUOTA = new ErrorCode(8020, "REGISTRY_TENANT_QUERY_QUOTA");

    /**
     * A registration that would start a computation for a tenant already holding its whole share of
     * state (ADR-050).
     *
     * <p>State is counted in keys held by the tenant's views, which is the one measure each
     * computation owns alone and can report exactly. The quota is an admission bound: it refuses the
     * next computation, and does not shed or stop one that is running. A name attached to a
     * computation that already exists adds no state and is not refused by this.
     */
    public static final ErrorCode TENANT_STATE_QUOTA = new ErrorCode(8021, "REGISTRY_TENANT_STATE_QUOTA");

    /**
     * A replacement by a principal of a different tenant from the one the name belongs to
     * (ADR-050).
     *
     * <p>A name belongs to the tenant that registered it. A new version is a computation charged to
     * that tenant's quotas and shared only within it, so a principal of another tenant putting one
     * behind the name would either charge a tenant that did not ask for it or move the name out of
     * the tenant whose readers use it. Neither is done; the replacement is refused before the new
     * version is planned to run.
     */
    public static final ErrorCode TENANT_MISMATCH = new ErrorCode(8022, "REGISTRY_TENANT_MISMATCH");

    /** A tenancy quota this node refuses to start with: a negative limit, or a blank tenant name (ADR-050). */
    public static final ErrorCode TENANCY_MISCONFIGURED = new ErrorCode(8023, "REGISTRY_TENANCY_MISCONFIGURED");

    /**
     * A query cannot be dropped while other queries read its view (ADR-056). The refusal names them;
     * drop them first. There is no cascade: it would drop queries other people registered.
     */
    public static final ErrorCode QUERY_HAS_DEPENDANTS = new ErrorCode(8024, "REGISTRY_QUERY_HAS_DEPENDANTS");

    /** A query would read, through other queries, its own view (ADR-056). The refusal names the loop. */
    public static final ErrorCode QUERY_CYCLE = new ErrorCode(8025, "REGISTRY_QUERY_CYCLE");

    /**
     * Something about a query over a query that cannot be made exact and is refused rather than
     * approximated (ADR-056): replacing a member of a chain, reading a name that is being replaced,
     * a retention on a query over a view, or a restored checkpoint without the consumed answer.
     */
    public static final ErrorCode CHAIN_UNSUPPORTED = new ErrorCode(8026, "REGISTRY_CHAIN_UNSUPPORTED");

    /** A chain of queries over queries deeper than {@code QueryChains.MAX_DEPTH} levels (ADR-056). */
    public static final ErrorCode CHAIN_TOO_DEEP = new ErrorCode(8027, "REGISTRY_CHAIN_TOO_DEEP");

    /**
     * A registration over a binding whose source has one consumer at a time -- a {@code
     * postgres-cdc} replication slot, a {@code mysql-cdc} replica id -- while another query reads it
     * (CDCREPL-2). The refusal names the query holding it; a second query over the same table needs a
     * second binding with a slot of its own.
     */
    public static final ErrorCode SOURCE_HELD = new ErrorCode(8028, "REGISTRY_SOURCE_HELD");

    private RegistryErrors() {}
}
