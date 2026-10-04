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
package com.ash.messaging.pravaha.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.jspecify.annotations.Nullable;

/**
 * The slow hash for secrets a person chooses or a program keeps: passwords and API-key secrets.
 *
 * <p>Argon2id at m=64 MiB, t=3, p=4 (ADR-052, as MAYA's {@code kdf.py}), with PBKDF2-HMAC-SHA512 at
 * 600,000 iterations where Argon2 cannot run. The stored form names its own algorithm and parameters --
 * {@code argon2id$m=65536,t=3,p=4$<salt>$<hash>} -- so a hash made under one setting still verifies
 * after the setting changes, and {@link #needsRehash} says when a login should upgrade it.
 *
 * <p>Random tokens (sessions, reset tokens) do not come here: 256 random bits need no slow hash, and a
 * slow one on every request would be a cost with no benefit. They are {@link #sha256Hex}.
 */
public final class Kdf {

    public static final int ARGON2_MEMORY_KIB = 65536;
    public static final int ARGON2_ITERATIONS = 3;
    public static final int ARGON2_PARALLELISM = 4;
    public static final int PBKDF2_ITERATIONS = 600_000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private Kdf() {}

    /** Hashes {@code secret} with the strongest algorithm available. */
    public static String hash(String secret) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = argon2(secret, salt, ARGON2_MEMORY_KIB, ARGON2_ITERATIONS, ARGON2_PARALLELISM);
        return "argon2id$m=" + ARGON2_MEMORY_KIB + ",t=" + ARGON2_ITERATIONS + ",p=" + ARGON2_PARALLELISM + "$"
                + B64.encodeToString(salt) + "$" + B64.encodeToString(derived);
    }

    /** PBKDF2 form, for a deployment that cannot run Argon2, and for tests of the fallback. */
    static String hashPbkdf2(String secret) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return "pbkdf2-sha512$i=" + PBKDF2_ITERATIONS + "$" + B64.encodeToString(salt) + "$"
                + B64.encodeToString(pbkdf2(secret, salt, PBKDF2_ITERATIONS));
    }

    /** Whether {@code secret} is what {@code stored} was made from. Constant time in the comparison. */
    public static boolean verify(@Nullable String secret, @Nullable String stored) {
        if (secret == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = B64D.decode(parts[2]);
            expected = B64D.decode(parts[3]);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        byte[] actual;
        switch (parts[0]) {
            case "argon2id" -> {
                int m = param(parts[1], "m");
                int t = param(parts[1], "t");
                int p = param(parts[1], "p");
                actual = argon2(secret, salt, m, t, p);
            }
            case "pbkdf2-sha512" -> actual = pbkdf2(secret, salt, param(parts[1], "i"));
            default -> {
                return false;
            }
        }
        return MessageDigest.isEqual(actual, expected);
    }

    /** True when {@code stored} is weaker than what {@link #hash} makes today, so a login re-hashes it. */
    public static boolean needsRehash(@Nullable String stored) {
        return stored == null
                || !stored.startsWith("argon2id$m=" + ARGON2_MEMORY_KIB + ",t=" + ARGON2_ITERATIONS + ",p="
                        + ARGON2_PARALLELISM + "$");
    }

    /** SHA-256, hex: for random tokens, where a slow hash buys nothing. */
    public static String sha256Hex(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /** {@code bytes} random bytes as lower-case hex: a key's public id. */
    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    /** {@code bytes} random bytes, URL-safe base64 without padding. */
    public static String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static byte[] argon2(String secret, byte[] salt, int memoryKib, int iterations, int parallelism) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] out = new byte[HASH_BYTES];
        generator.generateBytes(secret.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }

    private static byte[] pbkdf2(String secret, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, iterations, HASH_BYTES * 8);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException impossible) {
            throw new IllegalStateException("PBKDF2WithHmacSHA512 is required of every JVM", impossible);
        }
    }

    private static int param(String params, String name) {
        for (String pair : params.split(",", -1)) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return Integer.parseInt(kv[1]);
            }
        }
        throw new IllegalArgumentException("no " + name + " in " + params);
    }
}
