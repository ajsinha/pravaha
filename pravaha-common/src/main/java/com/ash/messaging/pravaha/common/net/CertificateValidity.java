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
package com.ash.messaging.pravaha.common.net;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Whether a listener's own certificate is valid now (CERTEXP-1).
 *
 * <p>A node started on an expired certificate logged {@code over TLS} and every verifying client then
 * failed its handshake, with nothing on the node's side to say why. The certificate's dates are as
 * checkable at start as the pair itself (SX-17), so they are checked there: a certificate that has
 * expired or is not valid yet is refused, under the listener's own TLS code -- the no-leniency rule,
 * since a node that starts cannot serve a single verifying client -- and one that expires within
 * {@link #WARNING_WINDOW} is started with a WARN naming the date.
 */
public final class CertificateValidity {

    /** How far ahead an expiry is warned about at start. */
    public static final Duration WARNING_WINDOW = Duration.ofDays(30);

    private static final System.Logger LOG = System.getLogger(CertificateValidity.class.getName());

    private CertificateValidity() {}

    /**
     * Refuses {@code leaf} when it is outside its validity period at {@code now}, and warns when it
     * leaves it within {@link #WARNING_WINDOW}. A certificate that is not X.509 is not judged.
     *
     * @param file the file it came from, named in the refusal
     * @param setting the configuration key that names that file
     * @param code the listener's TLS refusal code
     */
    public static void requireCurrent(Certificate leaf, String file, String setting, ErrorCode code, Instant now) {
        if (!(leaf instanceof X509Certificate x509)) {
            return;
        }
        Instant notBefore = x509.getNotBefore().toInstant();
        Instant notAfter = x509.getNotAfter().toInstant();
        String subject = x509.getSubjectX500Principal().getName();
        if (now.isAfter(notAfter)) {
            throw new PravahaException(
                    code,
                    "the TLS certificate " + file + " (" + subject + ") expired at " + notAfter
                            + ": every client that verifies it would fail its handshake. Renew it and point "
                            + setting + " at the new one");
        }
        if (now.isBefore(notBefore)) {
            throw new PravahaException(
                    code,
                    "the TLS certificate " + file + " (" + subject + ") is not valid until " + notBefore
                            + ": every client that verifies it would fail its handshake until then. Check this "
                            + "machine's clock, or use a certificate valid now");
        }
        if (now.plus(WARNING_WINDOW).isAfter(notAfter)) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "the TLS certificate " + file + " (" + subject + ") expires at " + notAfter + ", within "
                            + WARNING_WINDOW.toDays() + " days; renew it before then, or this node will refuse to "
                            + "start on it (" + setting + ")");
        }
    }
}
