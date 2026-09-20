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
package com.ash.messaging.pravaha.common.config;

import com.ash.messaging.pravaha.api.ErrorCode;

/** The configuration error codes. Stable, documented, and never renumbered. */
public final class ConfigErrors {

    public static final ErrorCode FILE_UNREADABLE = new ErrorCode(1001, "CONFIG_FILE_UNREADABLE");
    public static final ErrorCode FILE_MALFORMED = new ErrorCode(1002, "CONFIG_FILE_MALFORMED");
    public static final ErrorCode UNRESOLVED_REFERENCE = new ErrorCode(1010, "CONFIG_UNRESOLVED_REFERENCE");
    public static final ErrorCode CIRCULAR_REFERENCE = new ErrorCode(1011, "CONFIG_CIRCULAR_REFERENCE");

    /**
     * References nested deeper than this resolver will walk (E-8).
     *
     * <p>Split from {@link #CIRCULAR_REFERENCE}, which it used to share. The depth guard fires on
     * raw nesting, independently of the real cycle detector, so a long <em>acyclic</em> chain was
     * refused with a message naming a circular reference that did not exist -- and an operator
     * reading it went looking for a loop. Two different problems with two different fixes:
     * a cycle is broken, a chain is flattened.
     */
    public static final ErrorCode REFERENCE_TOO_DEEP = new ErrorCode(1012, "CONFIG_REFERENCE_TOO_DEEP");

    public static final ErrorCode MISSING_REQUIRED = new ErrorCode(1020, "CONFIG_MISSING_REQUIRED");
    public static final ErrorCode NOT_A_NUMBER = new ErrorCode(1021, "CONFIG_NOT_A_NUMBER");
    public static final ErrorCode NOT_A_BOOLEAN = new ErrorCode(1022, "CONFIG_NOT_A_BOOLEAN");
    public static final ErrorCode NOT_A_DURATION = new ErrorCode(1023, "CONFIG_NOT_A_DURATION");
    public static final ErrorCode NOT_A_DATA_SIZE = new ErrorCode(1024, "CONFIG_NOT_A_DATA_SIZE");
    public static final ErrorCode NOT_AN_ENUM = new ErrorCode(1025, "CONFIG_NOT_AN_ENUM");
    public static final ErrorCode OUT_OF_RANGE = new ErrorCode(1026, "CONFIG_OUT_OF_RANGE");

    /**
     * A key is in the configuration file and never reached the code that would have read it.
     *
     * <p>CFG-3. Spring's relaxed binder canonicalises a map key before it binds it, and a key it
     * cannot canonicalise is dropped -- so {@code pravaha.streams.txnü} and {@code
     * pravaha.streams."txn "} were present in the file, syntactically valid, absent from the
     * catalog, and reported at no log level at all. The query against them then failed with
     * "Object 'txnü' not found. Known streams: [...]", which is accurate and unhelpable.
     *
     * <p>The remedy is Spring's own bracket form, so the message names it.
     */
    public static final ErrorCode KEY_UNREACHABLE = new ErrorCode(1027, "CONFIG_KEY_UNREACHABLE");

    // PRV-1029 CONFIG_DOCS_BASE_URL_INVALID is allocated here and declared in
    // com.ash.messaging.pravaha.api.HelpUrls, for the same reason as PRV-1028 below: the base URL
    // for a failure's help page is read by pravaha-api itself, which cannot see this class
    // (DOCX-21).

    // PRV-1028 CONFIG_SCHEMA_MALFORMED is allocated here and declared in
    // com.ash.messaging.pravaha.plugin.filesystem.DelimitedCodec, because the `name:TYPE,name:TYPE`
    // schema grammar lives in the filesystem plugin and a plugin depends on pravaha-api only -- it
    // cannot see this class. It is written down here so the number is not handed out twice and so a
    // reader looking for the configuration codes finds all of them (finding TY-8).

    private ConfigErrors() {}
}
