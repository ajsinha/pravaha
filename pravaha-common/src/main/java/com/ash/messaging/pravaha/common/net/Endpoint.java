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

/**
 * How a host and a port are written down so that whoever reads them can connect.
 *
 * <p>CFG-2(c). An address in a log line or advertised to a cluster is copied into a connection
 * string, and {@code ::1:19090} cannot be parsed as one: the colons of the address and the colon
 * before the port are the same character, so a reader -- human or machine -- cannot tell where the
 * host ends. RFC 3986 settled this with brackets and every client library follows it, so the one
 * place this project writes {@code host:port} follows it too, rather than each caller remembering.
 *
 * <p>Also the two host spellings that a configuration file can carry and an operator did not mean.
 * {@code java.net.InetAddress} accepts an abbreviated IPv4 address -- {@code 127} is 0.0.0.127,
 * {@code 10.1} is 10.0.0.1 -- which is a documented part of the standard C library's behaviour and
 * is never what somebody typing a bind address intended. Detecting it is not this class's job to
 * act on; naming it is, so the caller can refuse with its own key and its own code.
 */
public final class Endpoint {

    private Endpoint() {}

    /**
     * {@code host:port}, with an IPv6 literal bracketed.
     *
     * <p>A host that is already bracketed is left alone, so this is safe to apply twice.
     */
    public static String address(String host, int port) {
        return hostForUri(host) + ":" + port;
    }

    /** The host as it belongs in a URI: bracketed if it is an IPv6 literal, unchanged otherwise. */
    public static String hostForUri(String host) {
        if (host == null) {
            return "";
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            return host;
        }
        return isIpv6Literal(host) ? "[" + host + "]" : host;
    }

    /**
     * Whether {@code host} is an IPv6 literal.
     *
     * <p>By the one property that matters here and that no hostname and no IPv4 address has: more
     * than one colon. Parsing the address properly would mean resolving it, which is a DNS call
     * this must not make -- it is called while writing a log line.
     */
    public static boolean isIpv6Literal(String host) {
        if (host == null) {
            return false;
        }
        int first = host.indexOf(':');
        return first >= 0 && host.indexOf(':', first + 1) >= 0;
    }

    /**
     * Whether {@code host} is an abbreviated IPv4 address that will be read as something else.
     *
     * <p>{@code 127} binds 0.0.0.127 and {@code 10.1} binds 10.0.0.1. Both are legal inputs to
     * {@code InetAddress.getByName} and neither is what the operator wrote them for; the bind then
     * fails with "Cannot assign requested address" and says nothing about the reinterpretation.
     */
    public static boolean isAbbreviatedIpv4(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length >= 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * What {@code host} is read as once the abbreviation is expanded: {@code 127} -> {@code
     * 0.0.0.127}, {@code 10.1} -> {@code 10.0.0.1}.
     *
     * <p>The rule is the standard library's: the leading parts are taken a byte each and the last
     * one fills every byte that is left. Only meaningful for a host {@link #isAbbreviatedIpv4}
     * answers true for, and only accurate while each part fits a byte -- which is the case an
     * operator meets, and the message that carries it says "read as", not "is".
     */
    public static String expandedIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        StringBuilder expanded = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            expanded.append(parts[i]).append('.');
        }
        for (int i = parts.length; i < 4; i++) {
            expanded.append("0.");
        }
        return expanded.append(parts[parts.length - 1]).toString();
    }
}
