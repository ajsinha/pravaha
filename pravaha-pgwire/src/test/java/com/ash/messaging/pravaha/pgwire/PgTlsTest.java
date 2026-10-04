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
package com.ash.messaging.pravaha.pgwire;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PgTls#load}'s own validation -- the CFG-6 lesson applied to this gateway: a half-configured
 * TLS pair, or one this server cannot parse, must be refused loudly at the call that configures it,
 * not read into a field and quietly never used.
 */
class PgTlsTest {

    @TempDir
    private File dir;

    @Test
    void aCertificateWithNoKeyIsRefusedNamingTheMissingSetting() throws Exception {
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(dir);

        assertThatThrownBy(() -> PgTls.load(cert.certificatePem, null))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("pravaha.pgwire.tls.key");
    }

    @Test
    void aKeyWithNoCertificateIsRefusedNamingTheMissingSetting() throws Exception {
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(dir);

        // CFG-6(b)'s own failure, reproduced: the ordering used to dereference the certificate
        // first, so a key configured alone threw a NullPointerException naming this class's own
        // field rather than the setting the operator actually needs to look at.
        assertThatThrownBy(() -> PgTls.load(null, cert.privateKeyPem))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("pravaha.pgwire.tls.certificate");
    }

    @Test
    void neitherHalfConfiguredIsNotAskedAboutHere() {
        // PravahaPgWireServer simply never calls encryptedWith when nobody configured TLS -- this
        // class has no "both null means fine" case to test, because that call is never made.
        assertThatThrownBy(() -> PgTls.load(null, null))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("pravaha.pgwire.tls");
    }

    @Test
    void aMissingCertificateFileIsRefused() throws Exception {
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(dir);

        assertThatThrownBy(() -> PgTls.load(new File(dir, "does-not-exist.pem"), cert.privateKeyPem))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("not a readable file");
    }

    @Test
    void aPkcs1KeyIsRefusedWithConversionGuidance() throws Exception {
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(dir);
        File pkcs1 = new File(dir, "pkcs1.pem");
        Files.writeString(
                pkcs1.toPath(),
                "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n",
                StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> PgTls.load(cert.certificatePem, pkcs1))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PKCS#8")
                .hasMessageContaining("openssl pkcs8 -topk8 -nocrypt");
    }

    @Test
    void aKeyFileWithNoPemBlockAtAllIsRefused() throws Exception {
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(dir);
        File notPem = new File(dir, "not-pem.txt");
        Files.writeString(notPem.toPath(), "this is not a PEM file", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> PgTls.load(cert.certificatePem, notPem))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("no PEM block");
    }

    @Test
    void aWellFormedPairLoadsAndCanUpgradeASocketPair() throws Exception {
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(dir);

        PgTls tls = PgTls.load(cert.certificatePem, cert.privateKeyPem);

        assertThat(tls).isNotNull();
        // The full round trip -- a real TLS handshake completing over a real socket -- is proved by
        // PsqlSessionTest and JdbcClientTest against real clients; this only confirms loading a
        // genuine PEM pair succeeds and produces something PgWireConnection can hand a socket to.
    }

    @Test
    void anExpiredCertificateIsRefusedAtStartNamingTheDate() throws Exception {
        // CERTEXP-1: the node started on it, logged "over TLS", and every verifying client failed.
        SelfSignedTestCertificate expired = SelfSignedTestCertificate.generate(dir, "-10d", 1);
        assertThatThrownBy(() -> PgTls.load(expired.certificatePem, expired.privateKeyPem))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6206")
                .hasMessageContaining("expired at")
                .hasMessageContaining("pravaha.pgwire.tls.certificate");
    }

    @Test
    void aCertificateNotValidYetIsRefusedToo() throws Exception {
        SelfSignedTestCertificate early = SelfSignedTestCertificate.generate(dir, "+10d", 30);
        assertThatThrownBy(() -> PgTls.load(early.certificatePem, early.privateKeyPem))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("is not valid until");
    }
}
