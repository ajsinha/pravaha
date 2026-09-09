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

## Fonts and brand assets

The Pravaha name, flow mark and slogan are proprietary to Ashutosh Sinha and are not
licensed for third-party use. IBM Plex, referenced by the design system, is licensed
under the SIL Open Font License 1.1.

## Reporting

If you believe a component is used in a way inconsistent with its licence, contact
Ashutosh Sinha \<ajsinha@gmail.com\>.
