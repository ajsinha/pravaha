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
package com.ash.messaging.pravaha.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Where a deployment publishes the help page for a {@code PRV-nnnn} code, when it publishes one.
 *
 * <p>Until DOCX-21 this was a constant, {@code https://docs.pravaha.io/errors/}, and that host has
 * never been registered: every failure this product has ever reported ended in a link that resolves
 * to NXDOMAIN, and three tests asserted on it, so the build enforced it. A dead link in an error
 * message is worse than no link -- it spends the one line an operator reads on an errand that
 * cannot succeed.
 *
 * <p>So the base is configuration, and there is no default. Three spellings of one setting:
 *
 * <ul>
 *   <li>the server binds {@code pravaha.docs.base-url} from its environment, like every other
 *       {@code pravaha.*} key;
 *   <li>an embedded engine reads the same key out of its own {@code Configuration};
 *   <li>the CLI, a bare SDK client and anything else with no configuration file read the
 *       environment variable {@code PRAVAHA_DOCS_BASE_URL}.
 * </ul>
 *
 * <p><strong>Unset means no URL</strong>, not a guessed one: {@link #forCode(String)} answers the
 * empty string, the REST and Flight contracts carry {@code helpUrl} as an empty field, and every
 * line that would have printed a link prints {@link #lookupHint(String)} instead -- which names the
 * two places a code can be resolved offline, the console's help and
 * {@code docs/guides/TROUBLESHOOTING.md}.
 *
 * <p>A base that is not an absolute {@code http} or {@code https} URL is refused where it is
 * configured, with {@link #BASE_URL_INVALID}, rather than concatenated with a code into something
 * that only looks like a link.
 */
public final class HelpUrls {

    /** The configuration key, on the server and in an embedded engine's configuration. */
    public static final String KEY = "pravaha.docs.base-url";

    /** The same setting where there is no configuration file: the CLI, an SDK client, a script. */
    public static final String ENVIRONMENT_VARIABLE = "PRAVAHA_DOCS_BASE_URL";

    /**
     * {@code pravaha.docs.base-url} is set to something that is not an absolute http/https URL.
     *
     * <p>Allocated in the configuration range and declared here rather than in {@code ConfigErrors}
     * because {@code pravaha-common} depends on {@code pravaha-api} and not the other way round --
     * the same reason {@code PRV-1028} is declared in the filesystem plugin. {@code ConfigErrors}
     * records the allocation so the number is not handed out twice.
     */
    public static final ErrorCode BASE_URL_INVALID = new ErrorCode(1029, "CONFIG_DOCS_BASE_URL_INVALID");

    /** Null when unset. Otherwise an absolute http/https URL ending in {@code /}. */
    private static volatile String base;

    private HelpUrls() {}

    /**
     * Sets the base, or clears it when {@code raw} is null or blank.
     *
     * @throws PravahaException {@link #BASE_URL_INVALID} if the value is not an absolute http or
     *     https URL. The previously configured base is left alone when that happens.
     */
    public static void configure(String raw) {
        base = normalise(raw);
    }

    /** Sets the base from {@code PRAVAHA_DOCS_BASE_URL}, for a process with no configuration file. */
    public static void configureFromEnvironment() {
        configure(System.getenv(ENVIRONMENT_VARIABLE));
    }

    /**
     * Sets the base from a configured value, falling back to the environment variable.
     *
     * <p>The two are one setting, so the explicit one wins and neither is merged with the other.
     */
    public static void configureOrFromEnvironment(String configured) {
        if (configured != null && !configured.isBlank()) {
            configure(configured);
        } else {
            configureFromEnvironment();
        }
    }

    /** Whether this deployment publishes help pages at all. */
    public static boolean configured() {
        return base != null;
    }

    /** The configured base, ending in {@code /}, or the empty string when there is none. */
    public static String base() {
        String current = base;
        return current == null ? "" : current;
    }

    /**
     * The help page for a rendered code such as {@code PRV-2002}, or the empty string when this
     * deployment publishes none.
     */
    public static String forCode(String renderedCode) {
        String current = base;
        if (current == null || renderedCode == null || renderedCode.isBlank()) {
            return "";
        }
        return current + renderedCode.strip();
    }

    /** What a message says instead of a link: the two places the code can be looked up offline. */
    public static String lookupHint(String renderedCode) {
        String what = renderedCode == null || renderedCode.isBlank() ? "this code" : renderedCode.strip();
        return "look " + what + " up in the console's help under Errors, or in docs/guides/TROUBLESHOOTING.md";
    }

    /**
     * The one line a printed refusal adds under its message: the help URL when this deployment has
     * one, and otherwise how to resolve the code without a network.
     */
    public static String helpLine(String renderedCode) {
        return configured() ? forCode(renderedCode) : lookupHint(renderedCode);
    }

    private static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String written = raw.strip();
        URI uri;
        try {
            uri = new URI(written);
        } catch (URISyntaxException e) {
            throw refuse(written, e.getReason() == null ? "it is not a URI" : e.getReason());
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw refuse(written, "it has no scheme, so it is a path and not a URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw refuse(written, "its scheme is '" + scheme + "' and a help page is fetched over http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw refuse(written, "it names no host");
        }
        return written.endsWith("/") ? written : written + "/";
    }

    private static PravahaException refuse(String written, String why) {
        return new PravahaException(
                BASE_URL_INVALID,
                KEY + " is '" + written + "', which is not an absolute http or https URL: " + why
                        + ". The engine appends a code to it -- " + KEY + " + 'PRV-2002' -- to build the help "
                        + "link every failure carries, so this value would produce a link nobody can follow. "
                        + "Write the base of a page that resolves, for example "
                        + "http://localhost:17070/help/codes/, or leave the key unset: with no base the engine "
                        + "prints no URL at all and says to " + lookupHint(null) + ".");
    }
}
