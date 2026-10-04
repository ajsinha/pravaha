# Pravaha Java SDK

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

The Java client for [Project Pravaha](../../README.md): connect to a node over Arrow Flight SQL,
query a view, register continuous queries, subscribe to changes. It is built and released apart
from the server and contains nothing of it: an application that uses it takes no engine, no
Calcite, no Spring and no storage client.

```java
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.Row;

try (PravahaFlightClient client = PravahaFlightClient.connect("grpc+tls://pravaha:19090");
        QueryResult result = client.query("SELECT user_id, total FROM user_volume WHERE total > ?", 100)) {
    for (Row row : result) {
        System.out.println(row.getString("user_id") + " " + row.getLong("total"));
    }
}
```

A `DECIMAL(p, s)` column is a `BigDecimal` at the column's scale — `row.getBigDecimal("amount")`, or
`row.get(...)` — exactly as the engine holds it, never a `double` (FLIGHTDECIMAL-1, 2.1);
`row.getString(...)` writes its plain digits. A `?` compared with a `DECIMAL` column binds a
`BigDecimal` or an integer at the column's scale, and refuses a `double` or a value with more places
than the column rather than round it (DECPARAM-1). Every other type's Java class is in
[CONTINUOUS_QUERIES.md §16](../../docs/guides/CONTINUOUS_QUERIES.md#16-types).

**Nullness is in the types.** A parameter, return value or record component that may be null is
annotated with [JSpecify](https://jspecify.dev)'s `@Nullable`, and its javadoc says what null means;
anything without it is never null. Kotlin and NullAway read the annotations when `org.jspecify:jspecify`
is on the compile classpath, which it already is wherever the Flight client is, through Guava.

A connection string without a scheme is TLS; `grpc://host:port` is plaintext. A token goes in
`ClientOptions.builder(endpoint).token(...)`, and is refused over plaintext unless
`allowInsecureToken(true)` on the builder says otherwise.

**Every request has a deadline**: `ClientOptions.builder(endpoint).requestTimeout(Duration)`, 60
seconds unless set. It bounds `query(...)` up to its first batch — planning, preparing, binding and
opening the result — and every action: `register`, `queries`, `pause`, `resume`, `drop`, the
replacement and dead-letter calls, and the debugger's. A call past it throws
`PravahaClientException` with `ClientErrors.DEADLINE_EXCEEDED` (`PRV-1045`, `retryable()` true),
naming the call and the deadline. A subscription (`subscribe…`) gets no total deadline, since it is
meant to run for hours: only its opening is bounded — if the server has not answered with the
stream's schema in time, `awaitOpen()` or `run()` throws the same `PRV-1045` — and once open it runs
for as long as it runs. Reading the rows of a `QueryResult` that has opened is not bounded either.

The deadline also bounds **connecting**: the connection is made inside the first call, so a node
that cannot be reached, or accepts the TCP connection and never answers, fails that call with
`PRV-1045` at `requestTimeout` (or sooner, if the connection is refused outright). `connectTimeout`
is deprecated since 2.2.0 (CONNECTTIMEOUT-1): Arrow's Flight client builder has no setting it could
feed, so it never bounded anything. It is still accepted and validated, so 2.x code that sets it
compiles and runs; set `requestTimeout` instead.

## The artefacts

| Artefact | What it is | Depends on |
|---|---|---|
| `com.ash.messaging:pravaha-sdk-java-flight` | **The client.** `PravahaFlightClient` and its results, subscriptions and registry calls | `pravaha-sdk-java`; Arrow Flight SQL, gRPC, protobuf and Netty |
| `com.ash.messaging:pravaha-sdk-java` | The client's types: `ClientOptions`, `TlsOptions`, `Consistency`, `PravahaClientException` | `pravaha-api` only; an enforcer rule bans Netty and every engine module. JSpecify's `@Nullable` is compile-only |
| `com.ash.messaging:pravaha-api` | The public API types both share | nothing (JSpecify's `@Nullable` is compile-only) |
| `pravaha-sdk-java-flight-<version>-all.jar` | The client and every runtime dependency in one jar, for a client with no build tool | (bundled) |

All three artefacts, and so the `-all` jar, are Java 21 class files: **a client application needs
Java 21 or later** ([ADR-062](../../docs/design/adr/062-java-21-or-later.md)). In 1.x `pravaha-api` and `pravaha-sdk-java` targeted Java 17
and the Flight client Java 21; 2.0.0 made all three Java 25. The wire is unchanged, so an application that cannot move yet
can keep a 1.x client against a 2.x node meanwhile; the tested pairing is still client and
node of the same major.minor ([docs/operations/COMPATIBILITY.md](../../docs/operations/COMPATIBILITY.md#java-21-or-later)).

### With Maven or Gradle (the default)

```xml
<dependency>
  <groupId>com.ash.messaging</groupId>
  <artifactId>pravaha-sdk-java-flight</artifactId>
  <version>${pravaha.version}</version>
</dependency>
```

```kotlin
implementation("com.ash.messaging:pravaha-sdk-java-flight:$pravahaVersion")
```

This is the recommended way: the build tool sees the SDK's dependencies and reconciles them with the
application's own. The SDK names every Netty artifact it needs directly, so the application
resolves one Netty (the version the SDK is tested with) rather than the mix Arrow and gRPC would
otherwise bring (SDKNETTYMIX-1). Its dependency tree holds no Pravaha module but the three above;
`tools/sdk-standalone-check.sh` prints it.

### Without a build tool: the `-all` jar

```bash
javac -cp pravaha-sdk-java-flight-<version>-all.jar MyClient.java
java --add-opens=java.base/java.nio=ALL-UNNAMED -cp pravaha-sdk-java-flight-<version>-all.jar:. MyClient
```

About 19 MB. **Nothing in it is relocated**, on purpose: the SDK's own API hands out Arrow types
(`PravahaFlightClient.connect(options, BufferAllocator)`), which relocation would rename in this jar
only, and gRPC and Arrow find their transports and allocators through `META-INF/services` and class
names that relocation has to rewrite perfectly or fail at the first call. The services files of
all the bundled jars are merged. The consequence is the usual one for a fat jar: **do not put it on
a classpath that already has gRPC, Netty, Arrow, protobuf, Jackson or Guava**, or two copies of
those classes meet. An application with any of them uses the thin artifacts through its build tool.

It bundles `slf4j-api` but no binding, so the SDK logs nowhere until the application adds one
(SLF4J says so once, on stderr).

### Runtime flags

Arrow, underneath Flight, reads direct buffers' addresses, so it needs
`--add-opens=java.base/java.nio=ALL-UNNAMED` on the `java` command line (or in
`JDK_JAVA_OPTIONS`), with either kind of jar. Java 23 and later also print a warning that Netty calls a
deprecated `sun.misc.Unsafe` method; it is a warning, and `--sun-misc-unsafe-memory-access=allow`
silences it (older JVMs refuse that option, so add it only on 23 or later). The client is tested on Java 21 and 25.

## Building it

The SDK builds without the server:

```bash
tools/build-sdk.sh --java-only            # jars and POMs into target/sdk-dist/java/
tools/build-sdk.sh --java-only --install  # and into the local Maven repository
```

That builds the parent POM, `pravaha-api` and the two SDK modules and nothing else; the script
refuses to continue if Maven's reactor holds more. The SDK's own tests start a real server
in-process, so they run in the normal reactor build (`tools/worktree-build.sh -pl
sdk/pravaha-sdk-java-flight -am test`) and not in this one. See
[`docs/development/TESTING.md`](../../docs/development/TESTING.md#the-sdks-on-their-own).

`SdkIndependenceTest` (in `pravaha-it`) keeps it this way: it fails if either SDK module reaches a
Pravaha module other than `pravaha-api` at compile or runtime scope, if a server module depends on
an SDK, or if the server's executable jar contains SDK classes.

## Which server

Use the SDK from the same release as the server, the same major.minor version. The Flight protocol
carries no version handshake: a request an older server does not know (a verb added later, such as
`subscribeToAnswer`) is refused by that server with an error, never misread or silently downgraded.
Nothing checks the pairing when a client connects, and other pairings are not tested, so a mismatch
shows up as that refusal on the first new request. The Python SDK follows the same policy
([`sdk/python/README.md`](../python/README.md)).
