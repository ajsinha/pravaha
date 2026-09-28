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
package com.ash.messaging.pravaha.security;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Security error codes, PRV-7nnn. */
public final class SecurityErrors {

    /** No credential, or one that did not verify. */
    public static final ErrorCode UNAUTHENTICATED = new ErrorCode(7001, "SECURITY_UNAUTHENTICATED");

    /** Authenticated, but not permitted. */
    public static final ErrorCode FORBIDDEN = new ErrorCode(7002, "SECURITY_FORBIDDEN");

    /**
     * A row filter that cannot be enforced on the data available.
     *
     * <p>The refusal that keeps ADR-031 honest: a filter naming a column the view does not carry
     * cannot be applied to it, and the alternative to refusing is serving an aggregate that mixed in
     * rows this principal may not see.
     */
    public static final ErrorCode FILTER_NOT_ENFORCEABLE = new ErrorCode(7003, "SECURITY_FILTER_NOT_ENFORCEABLE");

    /**
     * A security setting this node refuses to start with.
     *
     * <p>E-3. {@code PRV-7002 FORBIDDEN} had grown four unrelated meanings across twenty sites: an
     * authorization denial, the startup refusal of an open server, the policy/authentication
     * contradiction, and a bad configuration <em>value</em>. {@code TROUBLESHOOTING.md}'s advice for
     * it -- "ask for access; a new credential will not help" -- is right for the first and wrong for
     * the rest. An operator who wrote {@code policy: permisive} was told to go and ask somebody for
     * permission.
     *
     * <p>The two are different in the way that matters most to whoever is reading: one is about a
     * caller and is answered by a grant, the other is about a file and is answered by an edit. They
     * do not belong to the same code however similar they look from inside the engine -- which is
     * how they ended up sharing one, a reasonable reuse at a time.
     */
    public static final ErrorCode MISCONFIGURED = new ErrorCode(7004, "SECURITY_MISCONFIGURED");

    /**
     * A sink write the policy allowed only in part.
     *
     * <p>SINK-3. {@link SecurityPolicy#mayWriteTo} answers about a destination, and a destination
     * takes a query's whole changelog or none of it: there is no row of it the engine could
     * withhold and still leave the table equal to the view. A decision that allows the write and
     * carries a row filter therefore describes something that cannot be done, and the only two
     * ways past it are to write the excluded rows anyway or to refuse. It refuses, at
     * registration, before the sink has been opened.
     *
     * <p>Not {@link #FORBIDDEN}, because nothing has been denied: the answer is one the engine
     * cannot carry out, so it is the policy that has to change and not the caller's entitlement.
     */
    public static final ErrorCode SINK_WRITE_NOT_FILTERABLE = new ErrorCode(7005, "SECURITY_SINK_WRITE_NOT_FILTERABLE");

    /**
     * A column masked for this reader used where its value would be compared rather than shown
     * (ADR-059 §4): a join key, a group key, a filter operand, a sort key, an aggregate's argument, a
     * view's key column or a subscription's tap filter.
     *
     * <p>A mask replaces what is served; comparing the column would let the reader learn the value's
     * equality classes or its order, which is what the mask exists to hide. Refused at plan time,
     * naming the column and the use.
     */
    public static final ErrorCode MASKED_COLUMN_USE = new ErrorCode(7006, "SECURITY_MASKED_COLUMN_USE");

    /**
     * An open subscription ended because the row filters or column masks that apply to its principal
     * changed (ADR-059 §8).
     *
     * <p>A stream's meaning does not change half-way: what the subscriber holds was filtered and masked
     * by the old policy, and the new one would make every later change mean something else. The client
     * subscribes again and starts from what the new policy lets it see.
     */
    public static final ErrorCode NARROWING_CHANGED = new ErrorCode(7007, "SECURITY_NARROWING_CHANGED");

    private SecurityErrors() {}
}
