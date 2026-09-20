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

    private SecurityErrors() {}
}
