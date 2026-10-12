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

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLContext;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginTls;

/**
 * A provider file's {@code tls.*} and {@code proxy.*} keys. TLS is the connectors' own vocabulary and code
 * ({@link PluginTls}, docs/guides/CONNECTOR_TLS.md): {@code tls.ca}, {@code tls.certificate} +
 * {@code tls.key}, {@code tls.truststore}, {@code tls.keystore}, their passwords as {@code ${ENV}}
 * placeholders; an unknown {@code tls.} key is refused rather than ignored. {@code tls.verify-hostname: false}
 * is refused: the JDK's HTTP client checks the name and cannot be told otherwise per client. A proxy is
 * {@code proxy.url} ({@code http://host:port}); an https target is tunnelled with CONNECT and keeps its TLS.
 * Proxy authentication is not built.
 */
final class ProviderTls {

    private ProviderTls() {}

    /** The context the file's {@code tls.*} keys describe, or empty when it has none. */
    static Optional<SSLContext> context(ProviderConfig c) {
        Map<String, String> tls = new LinkedHashMap<>();
        c.settings().forEach((k, v) -> {
            if (k.startsWith("tls.")) {
                tls.put(k, v);
            }
        });
        if (tls.isEmpty()) {
            return Optional.empty();
        }
        PluginContext ctx = new PluginContext() {
            @Override
            public Map<String, String> config() {
                return tls;
            }

            @Override
            public String instanceName() {
                return c.name();
            }
        };
        try {
            if (!PluginTls.verifyHostname(ctx)) {
                throw new LlmException(
                        LlmException.Kind.CONFIG,
                        "tls.verify-hostname: false is not supported: the JDK's HTTP client always checks the name; "
                                + "give the endpoint a name its certificate carries");
            }
            return PluginTls.from(ctx, AiErrors.CONFIG);
        } catch (LlmException e) {
            throw e;
        } catch (PravahaException e) {
            String why = e.getMessage();
            int cut = why.indexOf("  ");
            throw new LlmException(LlmException.Kind.CONFIG, cut > 0 ? why.substring(cut + 2) : why);
        }
    }

    /** The proxy {@code proxy.url} names, or empty for a direct connection. */
    static Optional<ProxySelector> proxy(ProviderConfig c) {
        if (!c.setting("proxy.username", "").isEmpty()
                || !c.setting("proxy.password", "").isEmpty()) {
            throw new LlmException(
                    LlmException.Kind.CONFIG, "proxy authentication (proxy.username, proxy.password) is not built");
        }
        String url = c.setting("proxy.url", "");
        if (url.isEmpty()) {
            return Optional.empty();
        }
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            if (host == null || u.getPort() <= 0) {
                throw new IllegalArgumentException();
            }
            return Optional.of(ProxySelector.of(InetSocketAddress.createUnresolved(host, u.getPort())));
        } catch (IllegalArgumentException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "proxy.url is not http://host:port");
        }
    }
}
