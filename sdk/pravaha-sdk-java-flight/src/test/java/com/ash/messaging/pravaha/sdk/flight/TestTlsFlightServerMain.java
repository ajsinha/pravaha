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
package com.ash.messaging.pravaha.sdk.flight;

import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

/**
 * A TLS-serving {@code PravahaFlightServer} with a known view, for the Python SDK's own TLS test.
 *
 * <p>The plaintext counterpart, {@code pravaha-flight}'s own {@code TestFlightServerMain}, has no
 * TLS mode -- adding one is a change to a module this round does not own. This lives in
 * {@code sdk/pravaha-sdk-java-flight} instead, alongside the {@link SelfSignedTestCertificate}
 * generator this SDK's own Java tests already use, and needs no change to {@code pravaha-flight} at
 * all: {@link PravahaFlightServer#encryptedWith} is already there to call.
 *
 * <p>Arguments: none. Prints the port and the CA certificate's path, each on its own line, so a
 * Python test harness can read them without parsing logs -- the same convention
 * {@code TestFlightServerMain} uses.
 */
public final class TestTlsFlightServerMain {

    private TestTlsFlightServerMain() {}

    public static void main(String[] args) throws Exception {
        StreamSchema schema = StreamSchema.builder("user_volume")
                .field("user_id", Types.string())
                .field("total", Types.int64())
                .build();
        ServedView view = new ServedView("user_volume", schema, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 10);
        view.applyValues(new Object[] {"u2", 50L}, 1, 10);
        view.commit(10);

        SelfSignedTestCertificate certificate = SelfSignedTestCertificate.generate();
        try (PravahaFlightServer server = new PravahaFlightServer(new ViewCatalog().register(view))
                .encryptedWith(
                        certificate.pemCertificate().toFile(),
                        certificate.pemPrivateKey().toFile())
                .start("localhost", 0)) {
            System.out.println("PRAVAHA_FLIGHT_TLS_PORT=" + server.port());
            System.out.println("PRAVAHA_FLIGHT_TLS_CERT=" + certificate.pemCertificate());
            System.out.println("PRAVAHA_FLIGHT_TLS_KEYSTORE=" + certificate.keystorePath());
            System.out.println("PRAVAHA_FLIGHT_TLS_KEYSTORE_PASSWORD=" + certificate.password());
            System.out.flush();
            Thread.currentThread().join();
        } finally {
            certificate.close();
        }
    }
}
