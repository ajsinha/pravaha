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
package com.ash.messaging.pravaha.server;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.catalog.Catalog;
import com.ash.messaging.pravaha.catalog.CatalogErrors;
import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.CatalogService;
import com.ash.messaging.pravaha.catalog.ObjectKind;
import com.ash.messaging.pravaha.identity.IdentityService;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.governance.CatalogProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;

/**
 * The node's Pravaha Catalog (ADR-059): opened once, with the migration rule applied, and handed out as
 * the one {@link CatalogPolicy} every transport authorizes against.
 *
 * <p>Kept out of {@link PravahaNode}, which is at the project's file-size ceiling; the node asks this
 * for its policy when the catalogue is on and tells it when the registry has recovered.
 *
 * <p><strong>The migration rule.</strong> With {@code authority: import} the first start imports
 * {@code pravaha.security.policy} as grants, once, and records that it did; a later start with a
 * different policy configured refuses with {@code PRV-7034}, because it would otherwise be running with
 * two authorities that disagree and silently obeying one. {@code authority: catalog} is the deployment
 * choosing the catalogue alone -- nothing imported, the setting ignored and said to be.
 */
final class NodeCatalog {

    private static final Logger log = LoggerFactory.getLogger(NodeCatalog.class);

    private final CatalogProperties properties;
    private final SecurityProperties security;
    private final Optional<Path> registryJournal;
    private final Supplier<AuditSink> audit;
    private final Supplier<Optional<IdentityService>> identity;
    private final StreamCatalog streams;
    private final SinkBindingProperties sinks;
    private final SourceBindingProperties sources;
    private @Nullable CatalogPolicy policy;

    NodeCatalog(
            CatalogProperties properties,
            SecurityProperties security,
            Optional<Path> registryJournal,
            Supplier<AuditSink> audit,
            Supplier<Optional<IdentityService>> identity,
            StreamCatalog streams,
            SinkBindingProperties sinks,
            SourceBindingProperties sources) {
        this.properties = properties;
        this.security = security;
        this.registryJournal = registryJournal;
        this.audit = audit;
        this.identity = identity;
        this.streams = streams;
        this.sinks = sinks;
        this.sources = sources;
    }

    boolean enabled() {
        return properties != null && properties.isEnabled();
    }

    /** The one policy, opening the catalogue and applying the migration rule the first time. */
    synchronized CatalogPolicy policy() {
        if (policy == null) {
            Catalog catalog = open();
            catalog.infrastructure(this::configured);
            migrate(catalog);
            CatalogService service = new CatalogService(catalog, audit.get());
            service.resolvingUsersWith(this::principalOf);
            policy = new CatalogPolicy(service, security.getAuditReaders());
        }
        return policy;
    }

    /** Whether a caller who proved nothing is served anything -- the catalogue's form of an open node. */
    boolean servesEveryone() {
        return policy().service().catalog().grantsToEveryone();
    }

    /**
     * After the registry has recovered: forgets views the catalogue records and the engine no longer
     * runs, and says once how this node is governed.
     */
    void started(QueryRegistry registry, boolean journalled) {
        Catalog catalog = policy().service().catalog();
        // ADR-059 §4: a binding is checked against the object's own columns before it is recorded.
        policy().service().policies().checkingWith(new PolicyCheck(registry, streams)::check);
        int forgotten = journalled ? catalog.reconcileViews(new HashSet<>(registry.names())) : 0;
        log.info(
                "catalog: governing access (ADR-059) from {}; authority={}, imported policy={}, {} objects, {} "
                        + "grants{}",
                catalog.journalFile().map(Path::toString).orElse("memory (grants are lost on restart)"),
                properties.authority(),
                catalog.importedPolicy().orElse("none"),
                catalog.objects().size(),
                catalog.grants().size(),
                forgotten == 0 ? "" : "; forgot " + forgotten + " views the registry no longer runs");
    }

    private Catalog open() {
        Optional<Path> file = properties.getJournal().isEmpty()
                ? registryJournal.map(path -> path.toAbsolutePath().resolveSibling("catalog.journal"))
                : Optional.of(Path.of(properties.getJournal()));
        if (file.isEmpty()) {
            log.warn("catalog: pravaha.catalog.journal is not set and there is no registry journal to keep it "
                    + "beside, so grants live only in memory and a restart loses them");
            return Catalog.inMemory(Clock.systemUTC());
        }
        return Catalog.open(file.get(), Clock.systemUTC());
    }

    private void migrate(Catalog catalog) {
        String configured = canonical(security.trimmedPolicy());
        if (properties.authority().equals("catalog")) {
            log.info(
                    "catalog: pravaha.catalog.authority=catalog, so pravaha.security.policy ({}) is not consulted; "
                            + "the catalogue's grants alone decide",
                    configured);
            return;
        }
        Optional<String> imported = catalog.importedPolicy();
        if (imported.isEmpty()) {
            if (!catalog.grants().isEmpty()) {
                throw twoAuthorities("the catalogue already holds "
                        + catalog.grants().size() + " grants and never " + "imported a policy, so importing '"
                        + configured + "' now would merge two authorities");
            }
            catalog.importPolicy(
                    configured,
                    CatalogPolicy.importedGrants(
                            configured, "import", Clock.systemUTC().instant()));
            log.info("catalog: imported pravaha.security.policy={} as grants, once", configured);
            return;
        }
        if (!imported.get().equals(configured)) {
            throw twoAuthorities("the catalogue imported pravaha.security.policy=" + imported.get() + " and the "
                    + "setting is now '" + configured + "'");
        }
        warnOfImportedAdministration(catalog);
    }

    /**
     * Said, not undone: an import written before ownership (pravaha.security.administer) granted MODIFY
     * on everything to every verified caller, which is the rule ownership replaces. Grants are the
     * catalogue's to change, by REVOKE, and the engine does not rewrite them behind the operator.
     */
    private void warnOfImportedAdministration(Catalog catalog) {
        boolean readersAdminister = catalog.grants().stream()
                .anyMatch(grant -> grant.object().equals(com.ash.messaging.pravaha.catalog.CatalogNames.ROOT)
                        && grant.privilege() == com.ash.messaging.pravaha.catalog.Privilege.MODIFY
                        && grant.grantee().role()
                        && grant.grantee().name().equals(com.ash.messaging.pravaha.catalog.Grantee.AUTHENTICATED));
        if (readersAdminister) {
            log.warn(
                    "catalog: the catalogue grants MODIFY ON CATALOG TO ROLE authenticated (imported before views were "
                            + "administered by their owners), so every verified caller may still drop, pause and replace "
                            + "every view. REVOKE MODIFY ON CATALOG FROM ROLE authenticated to leave that to owners, grantees and "
                            + "admins.");
        }
    }

    private static PravahaException twoAuthorities(String what) {
        return new PravahaException(
                CatalogErrors.TWO_AUTHORITIES,
                what + ". A node with two authorities for who may do what obeys one of them silently. Choose: set "
                        + "pravaha.catalog.authority=catalog to let the catalogue alone decide (change access with "
                        + "GRANT and REVOKE), or restore the setting it imported.");
    }

    private static String canonical(String policy) {
        return policy.equals("authenticated-only") ? "authenticated" : policy;
    }

    private Optional<Principal> principalOf(String id) {
        Optional<IdentityService> users = identity.get();
        if (users.isPresent()) {
            Optional<Principal> user = users.get().principalOfUser(id);
            if (user.isPresent()) {
                return user;
            }
        }
        return security.principalFor(id);
    }

    /** What the node is configured with, by kind, for a statement to name before anything recorded it. */
    private Map<ObjectKind, Collection<String>> configured() {
        Map<ObjectKind, Collection<String>> configured = new EnumMap<>(ObjectKind.class);
        List<String> streamNames = new ArrayList<>();
        streams.all().stream().map(StreamSchema::name).forEach(streamNames::add);
        configured.put(ObjectKind.STREAM, streamNames);
        configured.put(
                ObjectKind.LOOKUP,
                streams.lookups().stream().map(StreamSchema::name).toList());
        configured.put(
                ObjectKind.SINK,
                sinks == null ? List.of() : List.copyOf(sinks.getSinks().keySet()));
        configured.put(
                ObjectKind.SOURCE,
                sources == null ? List.of() : List.copyOf(sources.getSources().keySet()));
        return configured;
    }
}
