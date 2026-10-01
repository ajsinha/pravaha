# ADR-053: native code only where Java cannot do the job, and the build says which

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted, built. **Supersedes ADR-047's choice of base image** (Alpine); the rest of ADR-047 stands |
| Date | 2026-09-27 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-044 (no RocksDB), ADR-047 (the image), `../DEPLOYMENT.md` ("Native code") |

## Context

The owner asked that the build keep Pravaha portable and use JNI carefully. A native library is
platform code. It loads only on the builds its publisher made, usually glibc Linux on a few CPUs,
macOS and Windows, and it fails everywhere else. It fails at first use, not at startup.

An inventory of every compile and runtime dependency in the reactor found four families:

| Library | Came through | Platforms | A Java path? |
|---|---|---|---|
| `netty-tcnative-boringssl-static` | Arrow Flight (server, SDK, CLI); ZooKeeper | five builds | yes: the JDK's TLS engine, which gRPC falls back to |
| `netty-transport-native-epoll` | ZooKeeper | Linux x86_64 only | yes: NIO, which ZooKeeper uses unless told otherwise |
| `snappy-java` | Parquet (feedfile, delta) | glibc Linux, macOS, Windows, FreeBSD, SunOS | no: Parquet's Snappy codec binds to it |
| `zstd-jni` | Parquet (feedfile, delta) | glibc Linux, macOS, Windows, FreeBSD | no: Parquet's zstd codec binds to it |

**PORT-1.** ADR-047 put the image on Alpine (musl) and said: "if a connector ever brings a
glibc-only native library, the base changes". One had, through Parquet. Tested in the shipped base
image, snappy-java fails with `Error loading shared library ld-linux-x86-64.so.2`. Snappy is
Parquet's default codec, so a feedfile or Delta source could not read an ordinary Parquet file
inside the container. Nothing noticed, because the smoke journey reads CSV.

## Decision

1. **No native code where Java does the job.** `netty-tcnative-boringssl-static` is excluded where
   it enters, so TLS runs on the JDK's engine the same way on every platform. The Flight and SDK TLS
   end-to-end tests prove the handshake without it. `netty-transport-native-epoll` is excluded from
   ZooKeeper, which runs on NIO.
2. **Native code only where there is no Java path**, which is Parquet's two codecs. They stay, with
   their platforms written down (`DEPLOYMENT.md`, "Native code").
3. **The build enforces it.** An enforcer rule in the root POM (`enforce-portable-native-code`)
   refuses every known native family on a compile or runtime path: Netty's native transports and
   tcnative, JNA, JNR, lz4-java, RocksDB, Conscrypt, brotli4j and sqlite-jdbc. Test scope is exempt,
   because Testcontainers uses JNA to talk to Docker and never ships. Allowing a new family takes an
   ADR, not a POM edit.
4. **The node checks at startup.** `NativeCodecs` round-trips bytes through each codec when the node
   starts, and logs a warning naming the codec, the platform limits and the temp directory when one
   is present and does not load. It does not wait for the first Parquet file. `NativeCodecsTest`
   requires both to load on the build platform.
5. **The image moves to a glibc base**, `eclipse-temurin:21-jre` (Ubuntu), as ADR-047 pre-committed.
   It is 172 MB larger. `deploy/docker/smoke.sh` gains a step that loads both codecs inside the
   image, with a read-only root.
6. **The temp directory must allow execution.** Both codecs unpack their library into
   `java.io.tmpdir` and load it from there. A Kubernetes `emptyDir` allows that. Docker's `--tmpfs`
   defaults to `noexec`, so the documented read-only run says `--tmpfs /tmp:rw,exec`.

## Consequences

- Pravaha runs, TLS included, on any platform with a JDK 21. Reading or writing Snappy- or
  zstd-compressed Parquet additionally needs a platform the two codecs are built for. A node
  elsewhere says so at startup, and uncompressed or gzip Parquet (`java.util.zip`) still works.
- The image is about 172 MB larger than on Alpine. That is the price of a base the codecs load on.
- A dependency upgrade that brings a new native family fails the build and names it.
- The Kafka sink's codecs: lz4, snappy and zstd are refused by `kafka-sink` today. Enabling snappy
  and zstd there would reuse the two libraries already allowed, under the same platforms. lz4 would
  need `lz4-java`, which the rule refuses until an ADR allows it.
