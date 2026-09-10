# 03 — Embedded in a Java application

Mode A of design §22.2: the engine in your process, with no Spring, no cluster and no gateway.

```bash
./mvnw -q -DskipTests install

CP=$(./mvnw -q -pl pravaha-embedded dependency:build-classpath \
       -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime | grep -v '^\[' | tail -1)
CP="pravaha-embedded/target/classes:$CP"

javac -cp "$CP" -d /tmp/pravaha-example examples/03-embedded-java/Example.java
java  -cp "$CP:/tmp/pravaha-example" Example
```

Expected output:

```
engine   : example-engine
state    : RUNNING
lanes    : 4
plugins  : []
second   : second-engine RUNNING
```

## Why this matters

An embedded engine inherits its host application's JVM and its Spring version. That is exactly why
the engine core contains no Spring (ADR-019) and why `pravaha-embedded` has an enforcer rule that
fails the build if Spring ever reaches its classpath.

It is also why nothing here is a singleton: two engines run side by side with separate
configuration and separate plugin registries.
