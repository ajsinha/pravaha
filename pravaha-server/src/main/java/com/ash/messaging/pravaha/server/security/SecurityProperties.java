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
package com.ash.messaging.pravaha.server.security;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.security.TokenVerifier;

/**
 * {@code pravaha.security.*}: who may connect, and what they may see.
 *
 * <p>Every mechanism this configures was already built and tested -- {@code mayRead} on each view,
 * row filters applied to a <em>read</em>, per-source checks at registration, principals from
 * verified tokens, re-authorization on recovery. None of it was reachable, because the node constructed
 * {@code SecurityPolicy.PERMISSIVE} and {@code AuditSink.NONE} in the constructor and never called
 * {@code authenticatedBy} at all. The lock was built and the door was propped open.
 *
 * <p>The defaults are still open, because an engine embedded in a process that has already
 * authenticated its caller should not have to configure authentication twice. What is new is that
 * running open is now a <em>decision</em>: {@link #isAllowAnonymous()} must be set, and the node
 * refuses to start without it rather than serving everything to everybody quietly.
 *
 * <p>One thing this list used to claim and does not do: row filters are <em>not</em> honoured on
 * subscribe. A principal whose {@code AccessDecision} carries a row filter is refused by
 * {@code PravahaFlightSqlProducer.streamSubscription}, which is the right behaviour -- it replaced a
 * leak -- and means a policy returning row filters removes the ability to subscribe from every
 * conditionally-entitled principal. Reading the view still works and still applies the filter
 * (STRM-13).
 */
@Component
@ConfigurationProperties(prefix = "pravaha.security")
public class SecurityProperties {

    /** {@code none} or {@code token}. */
    private String authentication = "none";

    /**
     * {@code permissive} (everyone sees everything) or {@code authenticated} (only verified callers
     * see anything). Anything else is refused at startup with PRV-7004 -- {@link #validate()}, not
     * {@code PRV-7002}, which this said and which is {@code SECURITY_FORBIDDEN}.
     */
    private String policy = "permissive";

    /**
     * Who may drop, pause, resume, replace or debug a registered view. {@code ownership} (the default):
     * the principal who registered it, a principal the policy grants it to, or a holder of the {@code
     * admin} role -- the only value from 2.0. {@code legacy-read} (anyone whose read carries no row
     * filter, kept through 1.x) was removed in 2.0 and is refused at startup with PRV-7004 naming the
     * removal; anything else is refused with PRV-7004 too.
     */
    private String administer = "ownership";

    /**
     * {@code none}, {@code memory} or {@code file}. Anything else is refused at startup with
     * PRV-7004.
     *
     * <p>CFG-23. {@code memory} is for tests and for support reading a heap dump: it holds recent
     * events in this process and <em>nothing in the server exposes them</em>, so an operator asked
     * "who read payroll" cannot answer it from a running node. {@code file} is the setting that
     * produces a trail somebody can read -- see {@link #getAuditFile()}.
     */
    private String audit = "none";

    /**
     * Where {@code audit: file} writes, as JSON Lines.
     *
     * <p>Defaulted rather than required, because an audit setting whose only effect is a startup
     * failure is one that gets turned off again. {@code ./pravaha-audit.jsonl} is relative to the
     * working directory; a deployment should set this to somewhere it keeps records.
     *
     * <p>The file is created owner-only. It holds every principal id that asked for anything and
     * the SQL they asked with, which is the reason this is a file and not an endpoint: the
     * operating system already answers who may read it, and Pravaha's policy SPI has no question
     * that means "may read the audit trail".
     */
    private String auditFile = "pravaha-audit.jsonl";

    /** Rotate the audit file once it passes this many bytes. */
    private long auditRotateBytes = com.ash.messaging.pravaha.security.FileAuditSink.DEFAULT_ROTATE_BYTES;

    /** How many rotated audit files to keep; the oldest is deleted. */
    private int auditKeep = com.ash.messaging.pravaha.security.FileAuditSink.DEFAULT_KEEP;

    /**
     * The roles whose holders may read the audit trail over {@code GET /api/v1/audit}, under
     * {@code policy: authenticated}. {@code permissive} lets every caller read it, as it lets every
     * caller read and drop everything; a custom policy answers {@code mayReadAudit} itself.
     */
    private List<String> auditReaders = new java.util.ArrayList<>(List.of("admin"));

    /**
     * How many recent decisions the node keeps readable in memory, beside whatever
     * {@link #getAudit()} records durably. The read API names this bound and what it has evicted.
     */
    private int auditRecent = com.ash.messaging.pravaha.security.AuditTrail.DEFAULT_CAPACITY;

    /**
     * Acknowledges that this server serves everything to unauthenticated callers.
     *
     * <p>Deliberately awkward. Without it, a server configured with no authentication and a
     * permissive policy refuses to start, so an open deployment is something somebody wrote down
     * rather than something nobody noticed.
     */
    private boolean allowAnonymous;

    private Map<String, TokenSpec> tokens = new LinkedHashMap<>();

    public String getAuthentication() {
        return authentication;
    }

    public void setAuthentication(String authentication) {
        this.authentication = authentication;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getAdminister() {
        return administer;
    }

    public void setAdminister(String administer) {
        this.administer = administer;
    }

    /** The administer rule, validated; see {@link #trimmedPolicy()} for why the refusal lives here. */
    public com.ash.messaging.pravaha.security.Administration.Rule administerRule() {
        return com.ash.messaging.pravaha.security.Administration.Rule.parse(administer);
    }

    public String getAudit() {
        return audit;
    }

    public void setAudit(String audit) {
        this.audit = audit;
    }

    public String getAuditFile() {
        return auditFile;
    }

    public void setAuditFile(String auditFile) {
        this.auditFile = auditFile == null || auditFile.isBlank() ? "pravaha-audit.jsonl" : auditFile.trim();
    }

    public long getAuditRotateBytes() {
        return auditRotateBytes;
    }

    public void setAuditRotateBytes(long auditRotateBytes) {
        this.auditRotateBytes = auditRotateBytes;
    }

    public int getAuditKeep() {
        return auditKeep;
    }

    public void setAuditKeep(int auditKeep) {
        this.auditKeep = auditKeep;
    }

    public List<String> getAuditReaders() {
        return auditReaders;
    }

    public void setAuditReaders(List<String> auditReaders) {
        this.auditReaders =
                auditReaders == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(auditReaders);
    }

    public int getAuditRecent() {
        return auditRecent;
    }

    public void setAuditRecent(int auditRecent) {
        this.auditRecent = auditRecent;
    }

    public boolean isAllowAnonymous() {
        return allowAnonymous;
    }

    public void setAllowAnonymous(boolean allowAnonymous) {
        this.allowAnonymous = allowAnonymous;
    }

    /**
     * The credentials this node accepts, keyed by the bearer token itself.
     *
     * <p>Every entry needs an {@code id} (CFG-11): the key is the secret, and the id is what is
     * written to the audit trail and durably to the registry journal.
     *
     * <p><strong>An entry must carry at least one property.</strong> {@code x: {}} in YAML flattens
     * to no property at all, so Spring's binder never sees the key and this map never contains it
     * -- the credential is in the file, absent from the verifier chain, and there is nothing any
     * code in this process can look at to notice (CFG-10(a)). Requiring {@code id} is what removes
     * the reason to write one: the entry that used to mean "use the map key as the id" is now the
     * one entry that cannot be expressed, and the spelling that works is the spelling that is safe.
     */
    public Map<String, TokenSpec> getTokens() {
        return tokens;
    }

    public void setTokens(Map<String, TokenSpec> tokens) {
        this.tokens = tokens == null ? new LinkedHashMap<>() : tokens;
    }

    /**
     * Refuses an unusable value before anything is built on it.
     *
     * <p>CFG-21. Every one of these was already validated, and every refusal arrived from inside a
     * bean the servlet container was building: {@code verifier()} is first reached from the
     * {@code pravahaAuthentication} {@code FilterRegistrationBean}, and {@code policy} and
     * {@code audit} from the node's own start, so an operator who wrote {@code authentication:
     * tokens} read three lines about Tomcat failing to start and found the actual sentence -- which
     * is a good one -- four {@code Caused by:} levels down. Nothing was wrong with the diagnosis;
     * it was in the wrong place.
     *
     * <p>Here it fires while this properties object is being initialised, before any bean that
     * depends on it exists, so the failure names {@code SecurityProperties} and the message is the
     * first thing under it.
     */
    @jakarta.annotation.PostConstruct
    public void validate() {
        trimmedAuthentication();
        trimmedPolicy();
        administerRule();
        trimmedAudit();
        tokens.forEach((credential, spec) -> principalIdOf(credential, spec));
        tokens.keySet().forEach(SecurityProperties::refuseAnUnusableCredentialKey);
    }

    /**
     * Refuses a token-table key that will not be the credential the operator wrote (SX-14).
     *
     * <p>Two shapes, both from YAML rather than from anything Pravaha does, and both silent.
     *
     * <ul>
     *   <li><strong>A bare {@code yes:}, {@code on:}, {@code y:} or {@code off:}.</strong> YAML 1.1
     *       reads these as booleans, so the key binds as {@code "true"} or {@code "false"} and the
     *       credential an operator believes they configured is not one any client can present. Two
     *       of them in one table -- {@code yes:} and {@code on:} -- collapse to the same key and
     *       fail the whole file's load with a duplicate-key error that names neither line. This
     *       cannot catch that case, because the file never loads; it catches the single-key case,
     *       which is the one that starts a node that quietly authenticates nobody.
     *   <li><strong>Leading or trailing whitespace.</strong> Spring discards it while binding, so
     *       {@code " tok "} and {@code "tok"} are one entry and one of the two the operator wrote
     *       is gone. Where the whitespace does survive -- a quoted key, a programmatic map -- the
     *       credential contains a character no HTTP header will carry intact.
     * </ul>
     *
     * <p>Refused rather than trimmed. Trimming is the papering default the standing rule is about:
     * the operator either meant the whitespace, in which case silently removing it changes who can
     * authenticate, or did not, in which case saying so costs one restart and guessing costs an
     * afternoon.
     */
    private static void refuseAnUnusableCredentialKey(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.security.SecurityErrors.MISCONFIGURED,
                    "a key under pravaha.security.tokens is empty or all whitespace. The key is the bearer "
                            + "credential itself, and no client can present an empty one, so this entry "
                            + "authenticates nobody while making the table look populated.");
        }
        if ("true".equals(credential) || "false".equals(credential)) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.security.SecurityErrors.MISCONFIGURED,
                    "pravaha.security.tokens has a key of '" + credential + "'. If you wrote 'yes:', 'on:', "
                            + "'y:' or their negatives, YAML 1.1 reads them as booleans and the credential "
                            + "bound here is the word '" + credential + "' -- not what you typed, and not "
                            + "something a client will send. Quote the key to bind it verbatim: "
                            + "\"[yes]\": {id: ...}. Two such keys in one table collapse into one and fail "
                            + "the file's load outright, naming neither line.");
        }
        if (!credential.equals(credential.strip())) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.security.SecurityErrors.MISCONFIGURED,
                    "a key under pravaha.security.tokens begins or ends with whitespace. The key is the "
                            + "bearer credential, and whitespace around it does not survive an HTTP header or "
                            + "Spring's own property binding intact -- so the credential this node verifies "
                            + "would not be the one configured. Refused rather than trimmed: trimming it "
                            + "silently changes who can authenticate. The credential is "
                            + credential.length() + " characters long and is deliberately not printed here.");
        }
    }

    /** Whether callers must present a credential. */
    public boolean authenticates() {
        return "token".equalsIgnoreCase(trimmedAuthentication());
    }

    /**
     * The policy name, validated and lower-cased.
     *
     * <p>The refusal lives here rather than in the node so that {@link #validate()} can reach it:
     * the node resolves the policy during {@code start()}, which is after the web server is up.
     */
    public String trimmedPolicy() {
        String configured = policy == null ? "permissive" : policy.trim();
        String canonical = configured.toLowerCase(java.util.Locale.ROOT);
        if (!canonical.equals("permissive")
                && !canonical.equals("authenticated")
                && !canonical.equals("authenticated-only")) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.security.SecurityErrors.MISCONFIGURED,
                    "pravaha.security.policy is '" + configured + "', which is not a policy this node "
                            + "knows. Use 'permissive' or 'authenticated', or implement SecurityPolicy "
                            + "for rules of your own.");
        }
        return canonical;
    }

    /** The audit sink's name, validated and lower-cased. See {@link #trimmedPolicy()} for why here. */
    public String trimmedAudit() {
        String configured = audit == null ? "none" : audit.trim();
        String canonical = configured.toLowerCase(java.util.Locale.ROOT);
        if (!canonical.equals("none") && !canonical.equals("memory") && !canonical.equals("file")) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.security.SecurityErrors.MISCONFIGURED,
                    "pravaha.security.audit is '" + configured + "'; use 'none', 'memory' or 'file'. "
                            + "'file' writes JSON Lines to pravaha.security.audit-file and is the only "
                            + "one of the three that leaves a record anybody can read.");
        }
        return canonical;
    }

    /**
     * The principal id for one entry of the token table, refusing an entry that has none.
     *
     * <p>CFG-11. This used to be {@code spec.getId() == null ? entry.getKey() : spec.getId()}, and
     * the map key <em>is the bearer credential</em>. A deployment that wrote
     * {@code pravaha.security.tokens.s3cr3t-value: {}} and registered a query put the secret in two
     * durable places it did not choose: the audit trail, and the registry journal at
     * {@code pravaha.registry.journal}, where it survives restarts and backups. The credential is
     * correctly kept out of the startup log and out of {@code /actuator/env}, which made the
     * journal the only leak and an easy one to miss.
     *
     * <p>Required rather than derived, because there is no id this class can invent that is not
     * either the credential or a lie. It also removes the reason an operator would write an empty
     * mapping under a token key -- see {@link #getTokens()}.
     */
    private static String principalIdOf(String credential, TokenSpec spec) {
        String id = spec == null || spec.getId() == null ? "" : spec.getId().trim();
        if (id.isEmpty()) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.security.SecurityErrors.MISCONFIGURED,
                    "a credential under pravaha.security.tokens has no id. The id names the principal in "
                            + "the audit trail and, durably, in the registry journal as a query's owner -- and "
                            + "the map key it would otherwise fall back to is the bearer token itself, so the "
                            + "credential would be written to disk. Give every entry under "
                            + "pravaha.security.tokens an id of its own; the credential this is about is "
                            + credential.length() + " characters long and is deliberately not printed here.");
        }
        return id;
    }

    /**
     * The configured value, validated.
     *
     * <p>An authentication setting that fails open on a typo is worse than no setting at all:
     * "tokens", "basic" and "token " all silently meant `none`, so a deployment that believed it had
     * switched authentication on had switched nothing on. The neighbouring `policy` key trims and
     * refuses; this one did neither.
     */
    public String trimmedAuthentication() {
        String value = authentication == null ? "none" : authentication.trim();
        if (!value.equalsIgnoreCase("none") && !value.equalsIgnoreCase("token")) {
            throw new IllegalArgumentException("pravaha.security.authentication is '" + authentication
                    + "'; the values are 'none' and 'token'. A misspelling here would otherwise mean "
                    + "'none', so a node that looked authenticated would accept every caller.");
        }
        return value;
    }

    /**
     * The warning a node prints at startup when it can verify no credential, or empty.
     *
     * <p>CFG-10(b). {@link #verifier()} answers {@link TokenVerifier#rejectAll()} for {@code
     * authentication: token} with an empty {@code tokens} map, which is the right behaviour, and
     * the comment beside it has always said the reason should be visible "at startup rather than in
     * a support ticket about 401s". It was not: the node logged {@code authentication=token} and
     * nothing else, and the excellent sentence {@code rejectAll} carries arrived at the first call
     * -- which is the support ticket that comment wants to avoid.
     *
     * <p>A node refusing every caller and a node accepting the right ones are the same line of log
     * without this.
     */
    public java.util.Optional<String> unusableTokenTable() {
        if (!authenticates() || !tokens.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("pravaha.security.authentication=token with no entries under "
                + "pravaha.security.tokens: this node can verify no credential and refuses every "
                + "call with PRV-7001. Configure a token table, supply a TokenVerifier bean, or run "
                + "with authentication=none if something in front of this node already "
                + "authenticates.");
    }

    /**
     * Builds the verifier, or null when authentication is off.
     *
     * <p>Static tokens are for a development server and a test, and the type says so by name. A
     * deployment that needs real identity implements {@link TokenVerifier} against whatever issues
     * its credentials; this is the rung that makes the authenticated path reachable without one.
     */
    public TokenVerifier verifier() {
        if (!authenticates()) {
            return null;
        }
        if (tokens.isEmpty()) {
            // Rather than accepting nothing silently: a server that authenticates with no
            // credentials configured refuses every call, and the reason should be visible at
            // startup rather than in a support ticket about 401s.
            return TokenVerifier.rejectAll();
        }
        StaticTokenVerifier verifier = null;
        for (Map.Entry<String, TokenSpec> entry : tokens.entrySet()) {
            TokenSpec spec = entry.getValue();
            // CFG-11. Not `spec.getId() == null ? entry.getKey() : spec.getId()`: the map key is
            // the bearer credential, and this id is written to the registry journal.
            Principal principal = new Principal(
                    principalIdOf(entry.getKey(), spec),
                    spec.getTenant(),
                    new LinkedHashSet<>(spec.getRoles()),
                    spec.getClaims());
            verifier = verifier == null
                    ? StaticTokenVerifier.of(entry.getKey(), principal)
                    : verifier.and(entry.getKey(), principal);
        }
        return verifier;
    }

    /**
     * The configured identity with this id, if one is configured.
     *
     * <p>For journal recovery, which has only the owner's id and must reconstruct the identity the
     * registration was made under. The node used to fabricate one -- a role-less principal in tenant
     * "unknown" -- which any policy that inspects either correctly refused, so no query survived a
     * restart on a secured node. Fabricating an identity is the wrong failure: it either grants
     * authority nobody conferred, or, as here, silently denies everything.
     *
     * <p>Empty when the id is unknown here. The caller must then refuse the recovery rather than
     * invent a principal, because an owner this node cannot identify is one whose entitlements it
     * cannot check.
     */
    public java.util.Optional<Principal> principalFor(String id) {
        if (id == null || id.isBlank()) {
            return java.util.Optional.empty();
        }
        for (Map.Entry<String, TokenSpec> entry : tokens.entrySet()) {
            TokenSpec spec = entry.getValue();
            String configured = principalIdOf(entry.getKey(), spec);
            if (id.equals(configured)) {
                return java.util.Optional.of(new Principal(
                        configured, spec.getTenant(), new LinkedHashSet<>(spec.getRoles()), spec.getClaims()));
            }
        }
        return java.util.Optional.empty();
    }

    /** One credential and the identity it stands for. */
    public static class TokenSpec {

        /**
         * Who this credential is, and required (CFG-11).
         *
         * <p>It used to default to the map key, which is the credential -- so a query registered
         * with it wrote the secret into the registry journal as its owner, where it survives
         * restarts and backups.
         */
        private String id;

        private String tenant = "public";
        private Set<String> roles = new LinkedHashSet<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public Set<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles == null ? new LinkedHashSet<>() : new LinkedHashSet<>(roles);
        }

        /**
         * What a row filter reads with {@code session_attribute('<name>')} (ADR-059 §4): {@code region:
         * EU}. A policy that reads a claim this credential does not carry refuses its reads with {@code
         * PRV-7039} rather than guess.
         */
        private Map<String, String> claims = new LinkedHashMap<>();

        public Map<String, String> getClaims() {
            return claims;
        }

        public void setClaims(Map<String, String> claims) {
            this.claims = claims == null ? new LinkedHashMap<>() : new LinkedHashMap<>(claims);
        }
    }
}
