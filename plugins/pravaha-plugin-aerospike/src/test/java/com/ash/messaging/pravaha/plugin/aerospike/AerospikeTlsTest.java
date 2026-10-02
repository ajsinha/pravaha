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
package com.ash.messaging.pravaha.plugin.aerospike;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;

import com.aerospike.client.Host;
import com.aerospike.client.policy.ClientPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Aerospike wants its TLS in two places at once, and the interesting cases are all about what
 * happens when only one of them is set.
 */
final class AerospikeTlsTest {

    private static PluginContext context(Map<String, String> options) {
        return new PluginContext() {
            @Override
            public Map<String, String> config() {
                return options;
            }

            @Override
            public String instanceName() {
                return "ledger";
            }
        };
    }

    @Test
    void tlsOffMeansNoNameAndNoComplaint() {
        // The overwhelmingly common case: a plugin that has never been told about TLS must behave
        // exactly as it did before this class existed, which means an empty name and no throw.
        assertThat(AerospikeTls.tlsName(context(Map.of()))).isEmpty();
    }

    @Test
    void tlsOnWithoutANameIsRefusedByName() {
        // This is the whole reason the class exists. Aerospike validates the certificate against
        // tls-name and not against the address dialled, so leaving it out is not a detail the
        // client can paper over -- it fails at connect with a message naming neither half.
        assertThatThrownBy(() -> AerospikeTls.tlsName(context(Map.of("tls.enabled", "true"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("tls.name")
                .hasMessageContaining("aerospike.conf")
                .hasMessageContaining("not usually the hostname");
    }

    @Test
    void materialWithoutAnEnabledFlagStillCountsAsOn() {
        // Setting tls.ca and nothing else is plainly a request for TLS, so the missing tls.name is
        // still an error. The alternative -- quietly connecting in plaintext -- is the downgrade.
        assertThatThrownBy(() -> AerospikeTls.tlsName(context(Map.of("tls.ca", "/etc/ca.pem"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("tls.name");
    }

    @Test
    void anExplicitFalseWinsOverMaterialThatIsLyingAround() {
        // Config decides, in both directions. An operator who has left a tls.ca in the file and
        // then written tls.enabled: false has said what they want, and inference must not override
        // it -- nor should they get an error about a name they deliberately do not need.
        assertThat(AerospikeTls.tlsName(context(Map.of("tls.enabled", "false", "tls.ca", "/etc/ca.pem"))))
                .isEmpty();
    }

    @Test
    void aNameIsReturnedWhenBothHalvesArePresent() {
        assertThat(AerospikeTls.tlsName(context(Map.of("tls.enabled", "true", "tls.name", "aerospike-cluster"))))
                .isEqualTo("aerospike-cluster");
    }

    @Test
    void hostsCarryTheNameWhenThereIsOne() {
        // The name has to reach every host, not just the first, or a cluster whose second node is
        // contacted first fails a certificate check the first node passed.
        Host[] hosts = AerospikeHosts.parse("10.0.0.1:4333, 10.0.0.2:4333", "aerospike-cluster");
        assertThat(hosts).hasSize(2);
        assertThat(hosts).allSatisfy(host -> assertThat(host.tlsName).isEqualTo("aerospike-cluster"));
        assertThat(hosts[1].port).isEqualTo(4333);
    }

    @Test
    void hostsHaveNoNameWhenTlsIsOff() {
        // A non-null tlsName changes how the client connects, so the plaintext path must leave it
        // genuinely unset rather than set to the empty string.
        Host[] hosts = AerospikeHosts.parse("127.0.0.1:3000", "");
        assertThat(hosts[0].tlsName).isNull();
        assertThat(hosts[0].port).isEqualTo(3000);
    }

    @Test
    void applyLeavesThePolicyAloneWhenTlsIsOff() {
        ClientPolicy policy = new ClientPolicy();
        AerospikeTls.apply(context(Map.of()), policy);
        assertThat(policy.tlsPolicy).isNull();
    }

    @Test
    void applyInstallsTheContextWhenTlsIsOn(@TempDir Path dir) throws Exception {
        // An empty PKCS12 truststore is enough to prove the wiring: the point under test is that
        // whatever PluginTls builds actually reaches ClientPolicy.tlsPolicy, not that this
        // particular trust material would verify any particular server.
        Path store = dir.resolve("trust.p12");
        KeyStore empty = KeyStore.getInstance("PKCS12");
        empty.load(null, null);
        try (OutputStream out = Files.newOutputStream(store)) {
            empty.store(out, "changeit".toCharArray());
        }
        ClientPolicy policy = new ClientPolicy();
        AerospikeTls.apply(
                context(Map.of(
                        "tls.enabled", "true",
                        "tls.name", "aerospike-cluster",
                        "tls.truststore", store.toString(),
                        "tls.truststore.password", "changeit")),
                policy);
        assertThat(policy.tlsPolicy).isNotNull();
        assertThat(policy.tlsPolicy.context).isNotNull();
    }

    @Test
    void aTrailingSeparatorInTheHostListIsRefusedLikeAnyOtherMalformedEntry() {
        // SPLITTRAIL-1: String.split dropped trailing empty strings, so "h:3000:" read as h:3000 and a
        // trailing comma vanished, while the same slip in the middle of the list was refused.
        assertThatThrownBy(() -> AerospikeHosts.parse("10.0.0.1:3000,", ""))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("host:port");
        assertThatThrownBy(() -> AerospikeHosts.parse("10.0.0.1:3000:", ""))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("host:port");
    }
}
