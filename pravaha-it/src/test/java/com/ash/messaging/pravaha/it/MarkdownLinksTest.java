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

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every relative link and image in every markdown file in the repository points at something that
 * is there -- and, when it names a heading in another markdown file, at a heading that is there.
 *
 * <p>Written when {@code docs/} was sorted into folders (guides, operations, development, design,
 * publications, project) and several hundred links moved with it. Before that the only link check
 * was {@code DocumentationFreshnessTest}'s, over fifteen files and only targets with a file
 * extension; a moved document broke links in files nobody had listed. This one reads every tracked
 * {@code .md} file, so a document moved again, renamed or deleted fails the build in every place
 * that pointed at it, and the index in {@code docs/README.md} is never the only map.
 *
 * <p>What counts as a link: {@code [text](target)}, {@code ![alt](target)}, a reference definition
 * {@code [label]: target}, and {@code href}/{@code src} on an HTML {@code a} or {@code img}.
 * Fenced code and inline code are not links and are not read. External links ({@code https:},
 * {@code mailto:}, any scheme) are not fetched, and a site-absolute link ({@code /help/...}, which
 * the console serves) is not a file. An anchor is checked against the target's headings as GitHub
 * names them (lower case, punctuation dropped, spaces to hyphens, a repeat numbered), an explicit
 * {@code {#id}} on a heading (the console's help topics) and an {@code <a id>} or {@code <a name>}.
 */
class MarkdownLinksTest {

    /**
     * Links that are right where the file is read, which is not the repository. Each entry is a
     * file and the targets in it that are exempt, and says why. Keep it short.
     */
    private static final Map<String, Set<String>> READ_ELSEWHERE = Map.of(
            // The QA bundle's README: deploy/qa/bundle.sh copies the guides it names flat into the
            // bundle's own docs/, which is where this link resolves.
            "deploy/qa/README.md", Set.of("docs/PYTHON_API_GUIDE.md"));

    private static final Pattern INLINE = Pattern.compile("\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)");
    private static final Pattern REFERENCE =
            Pattern.compile("^ {0,3}\\[[^\\]]+\\]:\\s*<?([^\\s>]+)>?(?:\\s|$)", Pattern.MULTILINE);
    private static final Pattern HTML =
            Pattern.compile("<(?:a|img)\\b[^>]*?\\b(?:src|href)\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    /** An inline code span, which may run over lines but not over a blank one. */
    private static final Pattern CODE_SPAN = Pattern.compile("`[^`\\n]*(?:\\n(?![ \\t]*\\n)[^`\\n]*)*`");

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern EXPLICIT_ID = Pattern.compile("\\{#([^}\\s]+)\\}\\s*$");
    private static final Pattern HTML_ANCHOR = Pattern.compile("<a\\s+(?:id|name)\\s*=\\s*\"([^\"]+)\"");

    private final Map<Path, Set<String>> anchorCache = new HashMap<>();

    @Test
    void everyRelativeLinkInEveryMarkdownFileResolves() throws IOException {
        Path root = repoRoot();
        List<String> files = markdownFiles(root);
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String file : files) {
            Path path = root.resolve(file);
            if (!Files.isRegularFile(path)) {
                continue; // deleted in the working tree, not yet in the index
            }
            for (String target : targets(Files.readString(path, StandardCharsets.UTF_8))) {
                if (SCHEME.matcher(target).find()
                        || target.startsWith("/")
                        || READ_ELSEWHERE.getOrDefault(file, Set.of()).contains(target)) {
                    continue;
                }
                checked++;
                String problem = problem(root, path, target);
                if (problem != null) {
                    broken.add(file + " -> " + target + " (" + problem + ")");
                }
            }
        }
        assertThat(files).as("the markdown files this check reads").hasSizeGreaterThan(200);
        assertThat(checked).as("the relative links it checked").isGreaterThan(1_000);
        assertThat(broken)
                .as("relative links in markdown files that do not resolve")
                .isEmpty();
    }

    /** Why {@code target}, written in {@code from}, does not resolve -- or null when it does. */
    private String problem(Path root, Path from, String target) throws IOException {
        int hash = target.indexOf('#');
        String file = URLDecoder.decode(hash < 0 ? target : target.substring(0, hash), StandardCharsets.UTF_8);
        String anchor = hash < 0 ? "" : URLDecoder.decode(target.substring(hash + 1), StandardCharsets.UTF_8);
        Path resolved = file.isEmpty() ? from : from.getParent().resolve(file).normalize();
        if (!resolved.startsWith(root)) {
            return "outside the repository";
        }
        if (!Files.exists(resolved)) {
            return "no such file";
        }
        if (!anchor.isEmpty()
                && Files.isRegularFile(resolved)
                && resolved.toString().endsWith(".md")
                && !anchors(resolved).contains(anchor.toLowerCase(Locale.ROOT))) {
            return "no such heading";
        }
        return null;
    }

    /** Every link target in a markdown text, outside fenced and inline code. */
    static List<String> targets(String markdown) {
        String text = CODE_SPAN.matcher(withoutFences(markdown)).replaceAll("``");
        List<String> out = new ArrayList<>();
        for (Pattern pattern : List.of(INLINE, REFERENCE, HTML)) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                out.add(matcher.group(1));
            }
        }
        return out;
    }

    private static String withoutFences(String markdown) {
        StringBuilder out = new StringBuilder();
        boolean fenced = false;
        for (String line : markdown.split("\n", -1)) {
            if (FENCE.matcher(line).find()) {
                fenced = !fenced;
            } else if (!fenced) {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** The anchors a markdown file offers, lower-cased. */
    private Set<String> anchors(Path file) throws IOException {
        Set<String> cached = anchorCache.get(file);
        if (cached != null) {
            return cached;
        }
        Set<String> out = new HashSet<>();
        Map<String, Integer> seen = new HashMap<>();
        boolean fenced = false;
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n", -1)) {
            if (FENCE.matcher(line).find()) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                Matcher explicit = EXPLICIT_ID.matcher(heading.group(1));
                if (explicit.find()) {
                    out.add(explicit.group(1).toLowerCase(Locale.ROOT));
                }
                String slug = slug(heading.group(1));
                int repeat = seen.merge(slug, 1, Integer::sum) - 1;
                out.add(repeat == 0 ? slug : slug + "-" + repeat);
            }
            Matcher html = HTML_ANCHOR.matcher(line);
            while (html.find()) {
                out.add(html.group(1).toLowerCase(Locale.ROOT));
            }
        }
        anchorCache.put(file, out);
        return out;
    }

    /** A heading's anchor as GitHub writes it. */
    static String slug(String heading) {
        String text = heading.replace("`", "")
                .replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("<[^>]+>", "")
                .strip()
                .toLowerCase(Locale.ROOT);
        return text.replaceAll("[^\\p{L}\\p{N}_\\- ]", "").replace(' ', '-');
    }

    /** The repository's tracked markdown files, or every one under the root when git is not to hand. */
    private static List<String> markdownFiles(Path root) throws IOException {
        try {
            Process git = new ProcessBuilder("git", "ls-files", "-z", "--", "*.md")
                    .directory(root.toFile())
                    .redirectErrorStream(false)
                    .start();
            byte[] listed;
            try (InputStream in = git.getInputStream()) {
                listed = in.readAllBytes();
            }
            if (git.waitFor(60, TimeUnit.SECONDS) && git.exitValue() == 0 && listed.length > 0) {
                return List.of(new String(listed, StandardCharsets.UTF_8).split("\0"));
            }
        } catch (IOException e) {
            // no git on the path: walk the tree instead
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !skipped(root.relativize(p)))
                    .map(p -> root.relativize(p).toString())
                    .toList();
        }
    }

    /** Build output, virtual environments, dependencies and agent worktrees: not the repository's. */
    private static boolean skipped(Path relative) {
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            String name = relative.getName(i).toString();
            if ((name.startsWith(".") && !name.equals(".github"))
                    || name.equals("target")
                    || name.equals("node_modules")
                    || name.equals("__pycache__")) {
                return true;
            }
        }
        return false;
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(".git"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("cannot locate the repository root");
        }
        return p;
    }
}
