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
package com.ash.messaging.pravaha.flight;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TLS is one setting with two halves, and half of it is not a weaker version of it.
 *
 * <p>CFG-6. {@code encryptedWith} validated each file's readability and neither argument's
 * existence, so a certificate configured without a key dereferenced null — and the helpful-NPE text
 * names {@code privateKey}, a field of this class, rather than {@code pravaha.flight.tls.key}, which
 * is the thing an operator has to set. The mirror case was worse and silent: a key without a
 * certificate never reached here at all, because the node's own branch tested only the certificate,
 * so the node started in plaintext with a private key configured and ignored.
 *
 * <p>Both now refuse, and the refusal names the configuration keys rather than the fields.
 */
class TlsPairTest {

    private static File readable(Path dir, String name) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, "-----BEGIN-----\n");
        return file.toFile();
    }

    @Test
    void aCertificateWithNoKeyIsRefusedByNameRatherThanThrowingNull(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> new PravahaFlightServer(new com.ash.messaging.pravaha.serving.ViewCatalog())
                        .encryptedWith(readable(dir, "cert.pem"), null))
                .as("this threw a NullPointerException naming a private field; an operator reading that "
                        + "learns nothing about which setting they are missing")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6104")
                .hasMessageContaining("pravaha.flight.tls.key")
                .hasMessageContaining("only a certificate");
    }

    @Test
    void aKeyWithNoCertificateIsRefusedRatherThanQuietlyServingPlaintext(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> new PravahaFlightServer(new com.ash.messaging.pravaha.serving.ViewCatalog())
                        .encryptedWith(null, readable(dir, "key.pem")))
                .as("the silent half of CFG-6: a node configured with a private key and no certificate "
                        + "served grpc:// in clear text, and nothing said the key had been ignored")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6104")
                .hasMessageContaining("pravaha.flight.tls.certificate")
                .hasMessageContaining("only a private key");
    }

    @Test
    void theRefusalSaysWhatToDoAboutIt(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> new PravahaFlightServer(new com.ash.messaging.pravaha.serving.ViewCatalog())
                        .encryptedWith(readable(dir, "cert.pem"), null))
                .as("an operator who set one half needs to be told to set both or neither, not merely "
                        + "that something was null")
                .hasMessageContaining("together");
    }

    @Test
    void bothHalvesPresentIsStillAccepted(@TempDir Path dir) throws Exception {
        // The check must refuse a half-pair and nothing else. A null guard that also broke the
        // configured case would trade a silent plaintext server for a node that cannot start.
        assertThatCode(() -> new PravahaFlightServer(new com.ash.messaging.pravaha.serving.ViewCatalog())
                        .encryptedWith(readable(dir, "cert.pem"), readable(dir, "key.pem")))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnreadableFileIsStillReportedAsUnreadable(@TempDir Path dir) throws Exception {
        // The pre-existing branches must still be reachable: the null check is ordered before them,
        // so a mistake there would mask every readability error behind the new message.
        assertThatThrownBy(() -> new PravahaFlightServer(new com.ash.messaging.pravaha.serving.ViewCatalog())
                        .encryptedWith(dir.resolve("absent.pem").toFile(), readable(dir, "key.pem")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6104")
                .hasMessageContaining("is not a readable file");
    }
}
