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
package com.ash.messaging.pravaha.ai;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4, by hand, for {@link BedrockProvider}: about a hundred lines of the JDK's own
 * HMAC and SHA-256 instead of the AWS SDK (which Drishti used, and which would bring two dozen jars to a
 * server that otherwise has no AWS code). Signs {@code host}, {@code x-amz-date}, {@code content-type}
 * and, with temporary credentials, {@code x-amz-security-token}; the payload hash is the body's. The
 * canonical URI encodes each path segment again, as every service but S3 requires.
 */
final class SigV4 {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private SigV4() {}

    /**
     * The headers that authenticate one request.
     *
     * @param sessionToken empty for long-term keys
     */
    static Map<String, String> sign(
            String method,
            URI uri,
            byte[] payload,
            String contentType,
            String accessKey,
            String secretKey,
            String sessionToken,
            String region,
            String service,
            Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String date = amzDate.substring(0, 8);
        Map<String, String> signed = new TreeMap<>();
        signed.put("host", hostHeader(uri));
        signed.put("x-amz-date", amzDate);
        if (!contentType.isEmpty()) {
            signed.put("content-type", contentType);
        }
        if (!sessionToken.isEmpty()) {
            signed.put("x-amz-security-token", sessionToken);
        }
        StringBuilder canonicalHeaders = new StringBuilder();
        signed.forEach((k, v) ->
                canonicalHeaders.append(k).append(':').append(v.strip()).append('\n'));
        String signedHeaders = String.join(";", signed.keySet());
        String canonical = method + "\n" + canonicalUri(uri) + "\n" + canonicalQuery(uri) + "\n" + canonicalHeaders
                + "\n" + signedHeaders + "\n" + hex(sha256(payload));
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        String signature = hex(hmac(key, toSign));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("x-amz-date", amzDate);
        if (!sessionToken.isEmpty()) {
            out.put("x-amz-security-token", sessionToken);
        }
        out.put(
                "Authorization",
                "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                        + ", Signature=" + signature);
        return out;
    }

    static String hostHeader(URI uri) {
        String host = String.valueOf(uri.getHost());
        int port = uri.getPort();
        boolean standard = port < 0
                || ("https".equals(uri.getScheme()) && port == 443)
                || ("http".equals(uri.getScheme()) && port == 80);
        return standard ? host : host + ":" + port;
    }

    /** The raw path with each segment percent-encoded once more. */
    static String canonicalUri(URI uri) {
        String raw = uri.getRawPath();
        if (raw == null || raw.isEmpty()) {
            return "/";
        }
        List<String> parts = new ArrayList<>();
        for (String segment : raw.split("/", -1)) {
            parts.add(encode(segment));
        }
        return String.join("/", parts);
    }

    static String canonicalQuery(URI uri) {
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty()) {
            return "";
        }
        Map<String, String> sorted = new TreeMap<>();
        for (String pair : q.split("&", -1)) {
            int eq = pair.indexOf('=');
            sorted.put(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
        }
        List<String> out = new ArrayList<>();
        sorted.forEach((k, v) -> out.add(k + "=" + v));
        return String.join("&", out);
    }

    /** RFC 3986 unreserved characters stay; everything else is {@code %XX}. */
    static String encode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.'
                    || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(HEX.toHexDigits(b).toUpperCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the JDK has no SHA-256", e);
        }
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the JDK has no HmacSHA256", e);
        }
    }

    static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }
}
