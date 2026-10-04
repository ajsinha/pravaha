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
package com.ash.messaging.pravaha.server.identity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.SecurityErrors;

/**
 * The address a sign-in is counted against (LOCKENUM-1): the peer's, or -- when the peer is a proxy
 * this node trusts, such as the console -- the address it says it is signing in for.
 *
 * <p>Failures bar the source they came from, so every person signing in through the console would
 * otherwise be one source, and five wrong passwords typed into the console's sign-in page would bar
 * that account for everyone using it. {@code pravaha.identity.lockout.trusted-proxies} names the
 * proxies whose {@code X-Forwarded-For} is believed; from anyone else the header is ignored, since a
 * caller who could choose its own source could spread its guesses over invented ones.
 *
 * <p>Entries are addresses ({@code 10.0.0.7}, {@code ::1}) or CIDR blocks ({@code 172.16.0.0/12});
 * a malformed one stops the node with {@code PRV-7004}, as any other security setting does.
 */
public final class SignInSource {

    private final List<Block> trusted;

    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    private record Block(byte[] network, int bits) {
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            for (int i = 0; i < bits; i++) {
                int mask = 0x80 >>> (i % 8);
                if ((address[i / 8] & mask) != (network[i / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }

    public SignInSource(List<String> trustedProxies) {
        this.trusted = trustedProxies == null
                ? List.of()
                : trustedProxies.stream()
                        .filter(entry -> entry != null && !entry.isBlank())
                        .map(String::strip)
                        .map(SignInSource::block)
                        .toList();
    }

    /**
     * The source of a sign-in from {@code peer}, which sent {@code forwardedFor} (or null).
     *
     * <p>From a trusted proxy, the right-most address in the header that is not itself a trusted proxy
     * -- the one the nearest untrusted hop connected from; each hop appends, so anything to its left is
     * the client's own word. Otherwise the peer.
     */
    public String of(String peer, @Nullable String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank() || !isTrusted(peer)) {
            return peer;
        }
        String[] hops = forwardedFor.split(",", -1);
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].strip();
            if (hop.isEmpty() || hop.length() > 64) {
                return peer;
            }
            if (!isTrusted(hop)) {
                return hop;
            }
        }
        return peer;
    }

    private boolean isTrusted(String address) {
        if (trusted.isEmpty() || address == null || !literal(address)) {
            return false;
        }
        byte[] bytes;
        try {
            bytes = InetAddress.getByName(address).getAddress();
        } catch (UnknownHostException notAnAddress) {
            return false;
        }
        return trusted.stream().anyMatch(block -> block.contains(bytes));
    }

    /** Only a literal is resolved: a name would send a sign-in to DNS. */
    private static boolean literal(String address) {
        return address.matches("[0-9.]+") || (address.matches("[0-9a-fA-F:.]+") && address.contains(":"));
    }

    private static Block block(String entry) {
        int slash = entry.indexOf('/');
        String address = slash < 0 ? entry : entry.substring(0, slash);
        try {
            if (!literal(address)) {
                throw new UnknownHostException(address);
            }
            byte[] network = InetAddress.getByName(address).getAddress();
            int bits = slash < 0 ? network.length * 8 : Integer.parseInt(entry.substring(slash + 1));
            if (bits < 0 || bits > network.length * 8) {
                throw new NumberFormatException(entry);
            }
            return new Block(network, bits);
        } catch (UnknownHostException | NumberFormatException malformed) {
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    "pravaha.identity.lockout.trusted-proxies has '" + entry + "', which is neither an IP address "
                            + "nor an address/prefix-length block (10.0.0.0/8, ::1/128)");
        }
    }
}
