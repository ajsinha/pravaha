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

A connection string without a scheme is TLS; `grpc://host:port` is plaintext. A token goes in
`ClientOptions.builder(endpoint).token(...)`, and is refused over plaintext unless
`allowInsecureToken(true)` on the builder says otherwise.

## The artefacts

| Artefact | What it is | Depends on |
|---|---|---|
| `com.ash.messaging:pravaha-sdk-java-flight` | **The client.** `PravahaFlightClient` and its results, subscriptions and registry calls | `pravaha-sdk-java`; Arrow Flight SQL, gRPC, protobuf and Netty |
| `com.ash.messaging:pravaha-sdk-java` | The client's types: `ClientOptions`, `TlsOptions`, `Consistency`, `PravahaClientException` | `pravaha-api` only; an enforcer rule bans Netty and every engine module |
| `com.ash.messaging:pravaha-api` | The public API types both share | nothing |
| `pravaha-sdk-java-flight-<version>-all.jar` | The client and every runtime dependency in one jar, for a client with no build tool | (bundled) |

`pravaha-api` and `pravaha-sdk-java` target **Java 17**, the baseline of many applications a client
is embedded in. The Flight client, and so the `-all` jar, needs **Java 21**, like the engine.

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

Arrow, underneath Flight, reads direct buffers' addresses. On Java 17 and later it needs
`--add-opens=java.base/java.nio=ALL-UNNAMED` on the `java` command line (or in
`JDK_JAVA_OPTIONS`), with either kind of jar. Java 24 and later also print a warning that Netty
calls a deprecated `sun.misc.Unsafe` method; it is a warning.

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
[`docs/TESTING.md`](../../docs/TESTING.md#the-sdks-on-their-own).

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
