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
package com.ash.messaging.pravaha.server.governance;

import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.SecurityErrors;

/**
 * {@code pravaha.catalog.*}: whether the Pravaha Catalog governs this node (ADR-059), where it is kept,
 * and which authority wins when a deployment has a policy configured too.
 *
 * <pre>
 * pravaha:
 *   catalog:
 *     enabled: true
 *     journal: /opt/pravaha/data/catalog.journal   # default: catalog.journal beside the registry journal
 *     authority: import                            # or: catalog
 * </pre>
 *
 * <p><strong>Off by default</strong>, and deliberately. Turning the catalogue on changes who decides who
 * may do what -- from {@code pravaha.security.policy} to grants the engine keeps -- and every existing
 * deployment, test and embedded use was written against the policy. {@code authority: import} makes
 * switching it on safe: the configured policy is imported once as grants that mean exactly what it
 * meant ({@code permissive}: everything to everyone; {@code authenticated}: everything but grants and the
 * audit trail to every verified caller), so a node behaves the same the moment after as the moment
 * before, and from then on access changes by {@code GRANT} without a restart.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.catalog")
public class CatalogProperties {

    private boolean enabled;
    private String journal = "";
    private String authority = "import";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Where the catalogue is journalled; empty means beside the registry journal, or memory without one. */
    public String getJournal() {
        return journal;
    }

    public void setJournal(String journal) {
        this.journal = journal == null ? "" : journal.strip();
    }

    /**
     * {@code import} (the default): the first start imports {@code pravaha.security.policy} as grants,
     * and a later start refuses ({@code PRV-7034}) if that setting has since changed, because two
     * authorities disagreeing is the failure the catalogue exists to remove. {@code catalog}: the
     * catalogue alone decides, nothing is imported, and {@code pravaha.security.policy} is ignored.
     */
    public String getAuthority() {
        return authority;
    }

    public void setAuthority(String authority) {
        this.authority = authority;
    }

    /** The authority, validated and lower-cased; anything else is refused with {@code PRV-7004}. */
    public String authority() {
        String value = authority == null ? "import" : authority.strip().toLowerCase(Locale.ROOT);
        if (!value.equals("import") && !value.equals("catalog")) {
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    "pravaha.catalog.authority is '" + authority + "'; it is 'import' (the configured policy "
                            + "is imported into the catalogue once) or 'catalog' (the catalogue alone decides)");
        }
        return value;
    }

    @jakarta.annotation.PostConstruct
    public void validate() {
        authority();
    }
}
