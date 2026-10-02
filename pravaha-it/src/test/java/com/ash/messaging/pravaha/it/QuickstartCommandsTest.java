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
package com.ash.messaging.pravaha.it;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.cli.PravahaCli;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quickstart's own commands are run, from the quickstart, and their printed output is checked.
 *
 * <p>Written because the quickstart rotted twice while the build stayed green. Round DOCX found its
 * first two commands both failed (DOCX-1); this round found the dead-letter-queue example printing
 * {@code ok  3 in, 2 out / 1 rejected} for a command run against a file with no bad line in it,
 * which produced {@code ok  6 in, 6 out} and no rejects file at all (DOCR-3).
 *
 * <p>{@code ExamplesTest} could not have caught either. It never opens {@code QUICKSTART.md}: it
 * reads two example READMEs and hard-codes two quickstart-<em>shaped</em> command lines of its own,
 * so the document and the assertions drift apart the moment somebody edits one and not the other.
 * {@code HANDOVER.md} named the fix -- "extracting its commands from the file is the cheapest fix
 * available here" -- and this is it.
 *
 * <p>What it executes is the part of the quickstart that needs no server: {@code cd}, {@code printf
 * > file}, {@code cat} and the {@code pravaha-engine run} / {@code validate} / {@code explain} commands. A
 * block using anything else -- {@code pravaha-server}, {@code make}, {@code docker}, {@code register}
 * -- is skipped rather than half-run, and {@link #theServerlessPartOfTheQuickstartIsActuallyCovered}
 * pins how many blocks that leaves, so coverage cannot be quietly deleted by rewording a fence.
 *
 * <p>Blocks share one scratch directory, in document order, because the quickstart's examples do:
 * the file one block writes with {@code printf} is the file the next one reads.
 */
class QuickstartCommandsTest {

    /** How many runnable bash blocks the document is expected to have. Raise it, never lower it silently. */
    private static final int EXPECTED_RUNNABLE_BLOCKS = 4;

    @Test
    void everyServerlessQuickstartCommandRunsAndPrintsWhatTheDocumentSays(@TempDir Path scratch) throws IOException {
        List<Block> blocks = runnableBlocks();
        List<String> failures = new ArrayList<>();

        for (Block block : blocks) {
            StringBuilder printed = new StringBuilder();
            int lastExit = 0;
            for (List<String> command : block.commands()) {
                lastExit = execute(command, scratch, printed);
            }

            String expected = block.expectedOutput();
            if (expected.isBlank()) {
                continue;
            }

            boolean shouldRefuse = expected.contains("PRV-");
            if (shouldRefuse && lastExit == 0) {
                failures.add(block.where() + ": the document prints a PRV- refusal, the command exited 0");
            }
            if (!shouldRefuse && lastExit != 0) {
                failures.add(block.where() + ": the document prints success, the command exited " + lastExit
                        + ". It printed: " + printed);
            }

            // The document's paths are relative to the reader's working directory; this test rewrote
            // them into the scratch, and the CLI echoes back what it was given. Put them back before
            // comparing, or every path in a documented line looks wrong.
            String asPrinted = printed.toString()
                    .replace(scratch + java.io.File.separator, "")
                    .replace(scratch.toString(), "");
            String actual = normalise(asPrinted);
            for (String fragment : fragmentsOf(expected)) {
                if (!actual.contains(fragment)) {
                    failures.add(block.where() + ": the document prints \"" + fragment
                            + "\" and the command did not. It printed: " + asPrinted);
                }
            }
        }

        assertThat(failures).as("""
                        docs/guides/QUICKSTART.md prints output its own commands do not produce.

                        The quickstart is the first thing an evaluator runs, and for a closed-source
                        product they cannot fall back to reading the code. Fix the document against a
                        real run, or fix the command.""").isEmpty();
    }

    @Test
    void theServerlessPartOfTheQuickstartIsActuallyCovered() throws IOException {
        // A check that silently stops checking is worse than no check. If a rewrite drops the
        // runnable examples below this, it is a deliberate decision and should read as one.
        assertThat(runnableBlocks())
                .as(
                        "the quickstart must keep at least %d command blocks that run without a server",
                        EXPECTED_RUNNABLE_BLOCKS)
                .hasSizeGreaterThanOrEqualTo(EXPECTED_RUNNABLE_BLOCKS);
    }

    // ------------------------------------------------------------------ the little interpreter

    /** One fenced {@code bash} block that this test can run, and the fenced block printed after it. */
    private record Block(int line, List<List<String>> commands, String expectedOutput) {

        String where() {
            return "docs/guides/QUICKSTART.md:" + line;
        }
    }

    private static List<Block> runnableBlocks() throws IOException {
        List<String> lines = Files.readAllLines(quickstart(), StandardCharsets.UTF_8);
        List<Block> blocks = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).strip().startsWith("```bash")) {
                continue;
            }
            int start = i + 1;
            int end = start;
            while (end < lines.size() && !lines.get(end).strip().startsWith("```")) {
                end++;
            }
            List<List<String>> commands = parse(lines.subList(start, end));
            i = end;
            if (commands == null) {
                continue; // uses something this test cannot run; skipped, not half-run
            }
            blocks.add(new Block(start, commands, outputFencedAfter(lines, end)));
        }
        return blocks;
    }

    /** The fenced block immediately following, which is what the document claims the commands print. */
    private static String outputFencedAfter(List<String> lines, int fenceEnd) {
        int i = fenceEnd + 1;
        while (i < lines.size() && lines.get(i).isBlank()) {
            i++;
        }
        if (i >= lines.size() || !lines.get(i).strip().equals("```")) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int j = i + 1; j < lines.size() && !lines.get(j).strip().startsWith("```"); j++) {
            text.append(lines.get(j)).append('\n');
        }
        return text.toString();
    }

    /** Commands for a block, or {@code null} if it uses anything this test refuses to guess at. */
    private static List<List<String>> parse(List<String> body) {
        List<List<String>> commands = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        for (String raw : body) {
            String line = raw.stripTrailing();
            if (line.endsWith("\\")) {
                joined.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            joined.append(line);
            String whole = joined.toString().strip();
            joined.setLength(0);
            if (whole.isEmpty() || whole.startsWith("#")) {
                continue;
            }
            List<String> words = tokenise(whole);
            if (words.isEmpty() || !isRunnable(words)) {
                return null;
            }
            commands.add(words);
        }
        return commands.isEmpty() ? null : commands;
    }

    private static boolean isRunnable(List<String> words) {
        String verb = words.get(0);
        if (verb.equals("cd") || verb.equals("cat")) {
            return words.size() == 2;
        }
        if (verb.equals("printf")) {
            return words.size() == 4 && words.get(2).equals(">");
        }
        return verb.equals("pravaha-engine")
                && words.size() > 1
                && Stream.of("run", "validate", "explain").anyMatch(words.get(1)::equals);
    }

    private static int execute(List<String> words, Path scratch, StringBuilder printed) throws IOException {
        return switch (words.get(0)) {
            case "cd" -> {
                // The quickstart steps into an example directory; bring its files to the scratch.
                Path from = repoRoot().resolve(words.get(1));
                try (Stream<Path> files = Files.list(from)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        Files.copy(
                                file,
                                scratch.resolve(file.getFileName().toString()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                yield 0;
            }
            case "printf" -> {
                Files.writeString(scratch.resolve(words.get(3)), unescape(words.get(1)), StandardCharsets.UTF_8);
                yield 0;
            }
            case "cat" -> {
                printed.append(Files.readString(scratch.resolve(words.get(1)), StandardCharsets.UTF_8));
                yield 0;
            }
            default -> {
                // Relative paths in the document are relative to the directory the reader is in.
                List<String> args = new ArrayList<>(words.subList(1, words.size()));
                for (int i = 0; i < args.size() - 1; i++) {
                    if (args.get(i).equals("--in")
                            || args.get(i).equals("--out")
                            || args.get(i).equals("--dlq")) {
                        args.set(i + 1, scratch.resolve(args.get(i + 1)).toString());
                    }
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                int code = new PravahaCli(
                                new PrintStream(out, true, StandardCharsets.UTF_8),
                                new PrintStream(err, true, StandardCharsets.UTF_8))
                        .run(args.toArray(String[]::new));
                printed.append(out.toString(StandardCharsets.UTF_8)).append(err.toString(StandardCharsets.UTF_8));
                yield code;
            }
        };
    }

    /** Shell words, honouring single and double quotes. Enough for the shapes above and no more. */
    private static List<String> tokenise(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started || word.length() > 0) {
                    words.add(word.toString());
                    word.setLength(0);
                    started = false;
                }
            } else {
                word.append(c);
            }
        }
        if (started || word.length() > 0) {
            words.add(word.toString());
        }
        return words;
    }

    private static String unescape(String format) {
        return format.replace("\\n", "\n").replace("\\t", "\t");
    }

    /**
     * The pieces of a documented output block that must appear in the real one.
     *
     * <p>Split on the ellipsis the document uses to elide the middle of a long message, and matched
     * with whitespace collapsed, because a quoted message is wrapped to the page width and the real
     * one is not.
     */
    private static List<String> fragmentsOf(String expected) {
        List<String> fragments = new ArrayList<>();
        for (String piece : normalise(expected).split("…|\\.\\.\\.")) {
            String trimmed = piece.strip();
            if (trimmed.length() > 3) {
                fragments.add(trimmed);
            }
        }
        return fragments;
    }

    private static String normalise(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static Path quickstart() {
        return repoRoot().resolve("docs/guides/QUICKSTART.md");
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(".git"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new UncheckedIOException(new IOException("cannot locate the repository root"));
        }
        return p;
    }
}
