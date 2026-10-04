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
package com.ash.messaging.pravaha.codegen;

import java.util.concurrent.atomic.AtomicLong;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.SimpleCompiler;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Compiles generated source into a live class.
 *
 * <p>Janino rather than {@code javac} for two reasons that both matter at registration time: it
 * compiles from a string with no filesystem involved, and it takes single-digit milliseconds rather
 * than seconds. Query registration is a stated product claim (W2: deploy under two seconds), and a
 * compiler that touches disk would spend most of that budget.
 *
 * <p><strong>Each stage gets its own classloader.</strong> That is what lets a dropped query's
 * generated class be unloaded; without it, metaspace grows with every registration and a
 * long-running node eventually dies of a leak nobody can attribute to anything (R12). The leak test
 * registers and drops ten thousand stages and asserts metaspace returns to baseline.
 *
 * <p>Compilation failure is never fatal to correctness. The caller falls back to the interpreted
 * path (design section 12.4), so a query still runs -- more slowly, and with a warning that says so.
 */
public final class StageCompiler {

    /**
     * The JVM refuses to JIT-compile a method larger than 8 kB of bytecode, and
     * {@code -XX:-DontCompileHugeMethods} is not an acceptable answer for a shipped product. Source
     * length is a rough proxy checked before compiling; the real limit is enforced by splitting.
     */
    static final int MAX_SOURCE_LINES = 4_000;

    private static final AtomicLong COUNTER = new AtomicLong();

    private final ClassLoader parent;

    public StageCompiler() {
        this(StageCompiler.class.getClassLoader());
    }

    public StageCompiler(ClassLoader parent) {
        this.parent = parent;
    }

    /**
     * Compiles and instantiates.
     *
     * @throws PravahaException with {@code PRV-3100} carrying the source, because a compilation
     *     failure in generated code is unreadable without seeing what was generated
     */
    public GeneratedStage compileFused(String simpleClassName, String source) {
        if (countLines(source) > MAX_SOURCE_LINES) {
            throw new PravahaException(
                    CodegenErrors.STAGE_TOO_LARGE,
                    "generated stage " + simpleClassName + " is " + countLines(source) + " lines, beyond the "
                            + MAX_SOURCE_LINES + "-line limit. The JVM will not JIT a method over 8 kB of "
                            + "bytecode, so this would run interpreted and be slower than the fallback.");
        }

        String className = simpleClassName + "$" + COUNTER.incrementAndGet();
        String finalSource = source.replace(simpleClassName, className);

        long start = System.nanoTime();
        try {
            // A fresh compiler, and therefore a fresh classloader, per stage. Sharing one would
            // pin every generated class for the life of the engine.
            SimpleCompiler compiler = new SimpleCompiler();
            compiler.setParentClassLoader(parent);
            compiler.cook(finalSource);

            Class<?> type = compiler.getClassLoader().loadClass(className);
            FusedStage processor = (FusedStage) type.getDeclaredConstructor().newInstance();
            long millis = (System.nanoTime() - start) / 1_000_000L;
            return new GeneratedStage(className, finalSource, processor, millis);

        } catch (CompileException e) {
            throw new PravahaException(
                    CodegenErrors.COMPILATION_FAILED,
                    "generated code did not compile: " + e.getMessage() + "\n--- generated source ---\n"
                            + numbered(finalSource),
                    e);
        } catch (ReflectiveOperationException e) {
            throw new PravahaException(
                    CodegenErrors.COMPILATION_FAILED,
                    "generated class " + className + " compiled but could not be instantiated: " + e,
                    e);
        }
    }

    /** Line numbers, because a compiler error citing line 47 is useless without them. */
    private static String numbered(String source) {
        String[] lines = source.lines().toArray(String[]::new);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%4d  %s%n", i + 1, lines[i]));
        }
        return sb.toString();
    }

    private static int countLines(String source) {
        return (int) source.chars().filter(c -> c == '\n').count();
    }
}
