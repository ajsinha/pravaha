# Third-party notices

**Project Pravaha** — Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>.
Proprietary and confidential; see [`LICENSE`](LICENSE).

Pravaha is proprietary software. It nonetheless depends on third-party open-source
components, each of which remains the property of its respective owners and is used
under its own licence. Those licences are unaffected by Pravaha's proprietary terms,
and several of them require that their attribution notices be preserved and
reproduced. This file discharges that obligation.

## Runtime dependencies — redistributed with the Software

| Component | Licence | Project |
|---|---|---|
| Agrona | Apache License 2.0 | https://github.com/aeron-io/agrona |
| JCTools | Apache License 2.0 | https://jctools.github.io |
| SLF4J | MIT License | https://www.slf4j.org |
| Apache Calcite | Apache License 2.0 | https://calcite.apache.org |
| Apache Calcite Avatica | Apache License 2.0 | https://calcite.apache.org/avatica |
| Apache Arrow | Apache License 2.0 | https://arrow.apache.org |
| Apache Ratis | Apache License 2.0 | https://ratis.apache.org |
| RocksDB (rocksdbjni) | Apache License 2.0 / GPLv2 (dual) | https://rocksdb.org |
| Janino | BSD 3-Clause | https://janino-compiler.github.io/janino |
| Netty | Apache License 2.0 | https://netty.io |
| gRPC-Java | Apache License 2.0 | https://grpc.io |
| Micrometer | Apache License 2.0 | https://micrometer.io |
| Spring Boot | Apache License 2.0 | https://spring.io/projects/spring-boot |
| HdrHistogram | CC0 1.0 / BSD 2-Clause (dual) | https://hdrhistogram.github.io |

## Build and test only — not redistributed

These are used to build, test and benchmark the Software and are not included in
any distributed artefact.

| Component | Licence | Project |
|---|---|---|
| JUnit 5 | Eclipse Public License 2.0 | https://junit.org/junit5 |
| AssertJ | Apache License 2.0 | https://assertj.github.io |
| jqwik | Eclipse Public License 2.0 | https://jqwik.net |
| ArchUnit | Apache License 2.0 | https://www.archunit.org |
| JMH | GPLv2 with Classpath Exception | https://openjdk.org/projects/code-tools/jmh |
| Apache Maven and plugins | Apache License 2.0 | https://maven.apache.org |
| Spotless / palantir-java-format | Apache License 2.0 | https://github.com/diffplug/spotless |
| Error Prone, NullAway | Apache License 2.0 / MIT | https://errorprone.info |
| JaCoCo | Eclipse Public License 2.0 | https://www.jacoco.org |
| Testcontainers | MIT License | https://testcontainers.com |

**JMH note.** JMH is GPLv2 with the Classpath Exception. It is a build- and
benchmark-time tool only, is confined to `pravaha-benchmarks`, and is never bundled
into or linked from a distributed artefact. Its licence therefore imposes no
obligation on the Software.

**RocksDB note.** RocksDB is dual-licensed Apache 2.0 / GPLv2; Pravaha relies on the
Apache 2.0 grant.

## Storage-engine clients

Client libraries for Aerospike, Cassandra/ScyllaDB, Redis, PostgreSQL and Kafka are
confined to their own plugin modules and are neither bundled with nor required by the
engine core. Each carries its own licence; see the `THIRD-PARTY-NOTICES.md` of the
plugin module concerned. Some are commercially licensed by their vendors, and using
them may require a separate agreement with that vendor.

## Console browser assets — vendored and redistributed

The console (`console/`) serves every script, stylesheet and font from its own
`web/static/vendor/` directory, so it renders with no network access (design §23.4a).
These files are redistributed unmodified, except that Monaco is a subset of its `min/`
build (the editor core, its loader and its editor worker; the language workers and
bundled grammars the console does not use are left out). Each directory carries the
upstream licence file named below.

| Component | Version | Licence | Licence file | Project |
|---|---|---|---|---|
| Monaco Editor | 0.56.0 | MIT License | `vendor/monaco/LICENSE`, `vendor/monaco/ThirdPartyNotices.txt` | https://github.com/microsoft/monaco-editor |
| Apache ECharts | 6.1.0 | Apache License 2.0 (includes code under the BSD 3-Clause licence of d3) | `vendor/echarts/LICENSE`, `vendor/echarts/NOTICE`, `vendor/echarts/LICENSE-d3` | https://echarts.apache.org |
| elkjs (Eclipse Layout Kernel) | 0.12.0 | Eclipse Public License 2.0 (used under EPL-2.0 of its EPL-2.0 OR GPL-3.0-or-later dual licence) | `vendor/elkjs/LICENSE.md` | https://github.com/kieler/elkjs |
| Preact (with Preact Hooks) | 10.29.8 | MIT License | `vendor/preact/LICENSE` | https://preactjs.com |
| htm | 3.1.1 | Apache License 2.0 | `vendor/htm/LICENSE` | https://github.com/developit/htm |
| Bootstrap | 5.3.8 | MIT License | `vendor/bootstrap/LICENSE` | https://getbootstrap.com |
| Bootstrap Icons | 1.x (an earlier 1.x build than 1.13.1) | MIT License | `vendor/bootstrap-icons/LICENSE` | https://icons.getbootstrap.com |

**elkjs note.** elkjs is offered under EPL-2.0 or GPL-3.0-or-later; Pravaha relies on
the EPL-2.0 grant. It is shipped as the unmodified upstream JavaScript file, so the
EPL-2.0 source-availability obligation is met by the file itself and the upstream
repository above.

## Fonts and brand assets

The Pravaha name, flow mark and slogan are proprietary to Ashutosh Sinha and are not
licensed for third-party use. The console's typefaces — Source Sans 3, Source Serif 4
and Source Code Pro, vendored under `console/web/static/vendor/fonts/` — and IBM Plex,
referenced by the design system, are licensed under the SIL Open Font License 1.1.

## Reporting

If you believe a component is used in a way inconsistent with its licence, contact
Ashutosh Sinha \<ajsinha@gmail.com\>.
