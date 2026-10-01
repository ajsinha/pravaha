# ADR-061: JDK 25 is the baseline from 2.0

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — **built**: every module, `pravaha-api` and the Java SDKs included, compiles to Java 25 class files (`maven.compiler.release=25`); the enforcer requires JDK 25 to build; the launchers refuse an older JVM by name; the images, the test runner, CI and the release script are on 25 only |
| Date | 2026-10-01 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-001 (language and platform) — **supersedes its baseline** (Java 21, `pravaha-api` at 17); ADR-047 and ADR-053 (the image's JRE); system design §4.5 |

## Decision

From Pravaha **2.0.0**, Java 25 LTS is the only JDK Pravaha is built, tested, run and released
on. Every module targets Java 25 — the engine, `pravaha-api`, `pravaha-sdk-java`,
`pravaha-sdk-java-flight`, the embedded engine and the Spring Boot starter alike. Nothing is built
or shipped on 21 any more: the 1.x `--java 21` image and its `-jre21` tag, the cross-JDK CI legs,
and the 17 target of the API and the thin Java SDK are gone.

This is the one breaking change of 2.0. Everything else 1.x called stable stays stable
([COMPATIBILITY.md](../../operations/COMPATIBILITY.md)).

## Context

ADR-001 chose 21 over an earlier 25 draft, with `pravaha-api` at 17, for one reason: an embeddable
library inherits its host's JVM, and in 2026-09 enterprise Java was largely on 17 and 21. 1.0
shipped on that baseline and added 25 as supported (Hadoop 3.4.3 for JEP 486, the launcher's
`--sun-misc-unsafe-memory-access=allow` and `--enable-native-access=ALL-UNNAMED` for JEPs 498 and
472, a four-leg built-with × run-on matrix), and on 2026-10-01 made 25 the images' default JRE.

Supporting two JDKs had become the cost of keeping 21, not of using 25: every launcher carried a
version gate because 21 refuses the 25 flags, every image build had a JRE choice, CI ran four legs
to prove a jar built on one ran on the other, and the code could use nothing after 21.

## Reasoning

- **The owner's decision**: "We are JDK 25 through and through." One JDK, everywhere.
- **25 is an LTS**, as 21 was. Moving the floor from one LTS to the next is the ordinary step, and
  a major version is where semantic versioning lets a floor move.
- **The work 25 needed is already done.** JEP 486 (no Security Manager; `Subject.getSubject`)
  was met by Hadoop 3.4.3; JEP 498 (warnings on `sun.misc.Unsafe` memory access) and JEP 472
  (warnings on native access) by the launcher's two options, which are now unconditional; the
  images have run 25 since 2026-10-01. 2.0 removes the 21 path rather than adding a 25 one.
- **One JDK removes branches, not just a matrix leg**: the launchers' version gate, the image's
  JRE argument and second tag, and the CI crossings.

## Consequences

- **Clients must be on Java 25.** An application embedding `pravaha-embedded`, using the Spring
  Boot starter, compiling a plugin against `pravaha-api`, or calling the engine through
  `pravaha-sdk-java(-flight)` needs JDK 25 to load 2.x jars (they are class-file version 69). This
  is ADR-001's embeddability cost, accepted knowingly. The wire (Flight SQL, the HTTP API, pgwire)
  does not change in 2.0, so an application that cannot move yet can keep a 1.x Java client
  against a 2.0 node while it does; the tested pairing is still client and node of the same
  major.minor ([COMPATIBILITY.md](../../operations/COMPATIBILITY.md)).
- **The Spring Boot starter needs Boot 3.4 or later.** Spring Framework 6.0 and 6.1 (Boot 3.2 and
  3.3) carry an ASM that cannot read class-file version 69: every starter test on those lines
  errors with "Unsupported class file major version 69" while Boot reads the auto-configuration's
  metadata (measured 2026-10-01). Boot 3.4 and 3.5 pass all 41 tests on JDK 25. The `boot-3.2` and
  `boot-3.3` profiles and CI legs are gone; this is a consequence of the Java baseline, not a
  second decision.
- **The Python SDK, the CLI and the console are unaffected**, except that their tests start a Java
  server and so need `JAVA_HOME` on a JDK 25.
- A node or `pravaha-engine` started on an older JVM stops with a message naming Java 25, rather
  than failing on the first class it loads (`UnsupportedClassVersionError`).
- The build tools (`tools/jdk25.sh`, sourced by every script that starts Maven) default
  `JAVA_HOME` to an installed JDK 25 and refuse an older one by name.
- Code may now use the language and library of 25. Nothing was rewritten to do so; comments that
  explain a choice by what Java 21 lacked stay true as history.
