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
package com.ash.messaging.pravaha.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed {@code --key value} and {@code --key=value} options.
 *
 * <p>Missing options report what was supplied and what was expected. A CLI is often someone's first
 * contact with the product, and "missing required option" on its own makes them read source they
 * may not have.
 */
final class Args {

    private final Map<String, String> options = new LinkedHashMap<>();
    private final List<String> positional = new java.util.ArrayList<>();

    private Args() {}

    static Args parse(List<String> arguments) {
        Args args = new Args();
        for (int i = 0; i < arguments.size(); i++) {
            String arg = arguments.get(i);
            if (!arg.startsWith("--")) {
                args.positional.add(arg);
                continue;
            }
            String body = arg.substring(2);
            int equals = body.indexOf('=');
            if (equals >= 0) {
                args.options.put(body.substring(0, equals), body.substring(equals + 1));
            } else if (i + 1 < arguments.size() && !arguments.get(i + 1).startsWith("--")) {
                args.options.put(body, arguments.get(++i));
            } else {
                // A bare --flag is a boolean.
                args.options.put(body, "true");
            }
        }
        return args;
    }

    Optional<String> get(String name) {
        return Optional.ofNullable(options.get(name));
    }

    String get(String name, String fallback) {
        return options.getOrDefault(name, fallback);
    }

    boolean has(String name) {
        return options.containsKey(name);
    }

    List<String> positional() {
        return List.copyOf(positional);
    }

    /**
     * A required option.
     *
     * @throws UsageException naming the option and listing what was actually supplied
     */
    String require(String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new UsageException(
                    "--" + name + " is required. Supplied: " + (options.isEmpty() ? "nothing" : options.keySet()));
        }
        return value;
    }

    /** Signals a command-line mistake, which exits 2 rather than 1. */
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
