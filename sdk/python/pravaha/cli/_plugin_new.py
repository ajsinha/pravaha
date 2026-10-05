"""``pravaha plugin new NAME --kind source|sink``: a Maven connector project against ``pravaha-api``,
with the TCK already wired, that builds and passes as generated.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

It follows docs/development/guides/CONNECTOR_DEVELOPMENT.md: a source is the guide's worked example
(section 8) under the project's own names, tested by ``SourcePluginTck`` with the kit's
``ArenaRowCollector``; a sink appends each row it is given to a file and is tested by
``SinkPluginTck`` (section 10). Either is registered for ``ServiceLoader`` in ``META-INF/services``,
depends on ``pravaha-api`` at ``provided`` scope (the node has it), and compiles for Java 21 (ADR-062).
The plugin versions in the POM are the ones this repository's build uses, so an offline build that
has built Pravaha has every one.
"""

from __future__ import annotations

import pathlib
import re

import pravaha
from pravaha.cli._common import Context, UsageError
from pravaha.cli._scaffold import Scaffold, emit

KINDS = ("source", "sink")
SPI = "com.ash.messaging.pravaha.api.plugin"


def _camel(name: str) -> str:
    return "".join(part.capitalize() for part in re.split(r"[^a-z0-9]+", name) if part)


def _substitute(text: str, values: "dict[str, str]") -> str:
    for key, value in values.items():
        text = text.replace(f"@{key}@", value)
    return text


POM = """\
<?xml version="1.0" encoding="UTF-8"?>
<!-- A Pravaha @KIND@ plugin, made by `pravaha plugin new`. See CONNECTOR_DEVELOPMENT.md. -->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>@PKG@</groupId>
  <artifactId>@ARTIFACT@</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>
  <name>Pravaha plugin: @NAME@</name>

  <properties>
    <!-- Production runs Java 21 (ADR-062): a plugin compiled for newer cannot load on the node. -->
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <!-- The engine's version: the plugin SPI and the TCK come from the same release. -->
    <pravaha.version>@VERSION@</pravaha.version>
    <junit.version>5.14.4</junit.version>
  </properties>

  <dependencies>
    <!-- provided: the node already carries pravaha-api; a second copy in plugins/ would clash. -->
    <dependency>
      <groupId>com.ash.messaging</groupId>
      <artifactId>pravaha-api</artifactId>
      <version>${pravaha.version}</version>
      <scope>provided</scope>
    </dependency>
    <!-- The conformance suite every shipped @KIND@ runs. -->
    <dependency>
      <groupId>com.ash.messaging</groupId>
      <artifactId>pravaha-testkit</artifactId>
      <version>${pravaha.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.16.0</version>
        <configuration>
          <compilerArgs>
            <arg>-Xlint:all</arg>
          </compilerArgs>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.6.0</version>
        <configuration>
          <!-- The opens the node's launcher gives: the testkit writes rows off-heap as a lane does. -->
          <argLine>--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED</argLine>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-resources-plugin</artifactId>
        <version>3.5.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <version>3.5.1</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-install-plugin</artifactId>
        <version>3.1.4</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-clean-plugin</artifactId>
        <version>3.5.0</version>
      </plugin>
    </plugins>
  </build>
</project>
"""

SOURCE_PLUGIN = """\
package @PKG@;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * The {@code @NAME@} source: the numbers 1..rows, each with its parity, one partition, replayable
 * offsets -- the connector guide's worked example under this project's names. Replace the reader
 * with your store's; keep the contract.
 *
 * <p>Bound on a node as
 * <pre>
 * pravaha.sources.numbers.plugin: @NAME@
 * pravaha.sources.numbers.options.rows: 10
 * </pre>
 */
public final class @CLS@SourcePlugin implements StreamSourcePlugin {

    /** A code of the plugin's own, in the plugin range (5000-5999). */
    static final ErrorCode BAD_CONFIGURATION = new ErrorCode(5990, "@CONST@_BAD_CONFIGURATION");

    private long rows;
    private StreamSchema schema = StreamSchema.builder("unconfigured").field("n", Types.int64()).build();
    private boolean open;

    @Override
    public String name() {
        return "@NAME@"; // what pravaha.sources.<stream>.plugin names; lower case, no spaces
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        // Validate here: a bad setting must not survive to the first row.
        String stream = context.get("stream", context.instanceName());
        this.rows = context.requireInt("rows");
        if (rows < 1) {
            throw new ConfigurationException(BAD_CONFIGURATION,
                    "@NAME@.rows is " + rows + "; it must be at least 1");
        }
        this.schema = StreamSchema.builder(stream)
                .field("n", Types.int64())
                .field("parity", Types.string())
                .build();
    }

    @Override
    public void open() {
        open = true;
    }

    @Override
    public void close() {
        open = false; // idempotent: the engine and the TCK may close twice
    }

    @Override
    public HealthStatus health() {
        return open ? HealthStatus.healthy() : HealthStatus.degraded("not open");
    }

    @Override
    public SourceCapabilities capabilities() {
        // Must answer on an unconfigured instance: `pravaha plugins` asks before configure.
        return new SourceCapabilities(
                true,                          // replayableOffsets: the offset is the next n
                true,                          // orderedWithinPartition
                false,                         // emitsDeletes
                false,                         // emitsBeforeImage
                DeliveryGuarantee.AT_LEAST_ONCE,
                EnumSet.noneOf(PushdownKind.class),
                Duration.ofMillis(1),
                false);                        // repeatsRows: each n is delivered once
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        return List.of(schema);
    }

    @Override
    public List<SourcePartition> partitions(String streamName) {
        return List.of(SourcePartition.of(streamName, 0));
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        // null means "from the configured position" (here, the first number); BEGINNING is "".
        long next = resumeFrom == null || resumeFrom.isBeginning() ? 1 : Long.parseLong(resumeFrom.token());
        return new @CLS@Reader(next, rows);
    }
}
"""

SOURCE_READER = """\
package @PKG@;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/** Reads 1..last from {@code next}. Non-blocking: zero when there is nothing more, never a wait. */
final class @CLS@Reader implements PartitionReader {

    private long next;
    private final long last;
    private volatile boolean paused;

    @CLS@Reader(long next, long last) {
        this.next = next;
        this.last = last;
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused) {
            return 0;
        }
        int produced = 0;
        while (produced < maxRecords && next <= last) {
            RowWriter row = sink.beginRow();          // a cell in the lane's inbox
            row.setLong(0, next);
            row.setString(1, next % 2 == 0 ? "even" : "odd");
            row.weight(1L)                             // +1: an insert
                    .eventTimestampNanos(next * 1_000_000_000L)
                    .sequence(next)
                    .commit();
            next++;
            produced++;
        }
        return produced;                              // 0 means "not now", never "never"
    }

    @Override
    public SourceOffset position() {
        return new SourceOffset(Long.toString(next)); // where a restart resumes
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    @Override
    public void close() {}
}
"""

SOURCE_TCK = """\
package @PKG@;

import java.util.Map;

import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

/**
 * The source TCK: every fixture record once, non-blocking polls, pause and resume, offsets that
 * replay, consistent capabilities, idempotent close. Rows are collected by the kit's
 * ArenaRowCollector over the plugin's first discovered schema; override newCollector for another.
 */
class @CLS@SourceTckTest extends SourcePluginTck {

    private record Context(String instanceName, Map<String, String> config) implements PluginContext {}

    @Override
    protected StreamSourcePlugin createPlugin() {
        @CLS@SourcePlugin plugin = new @CLS@SourcePlugin();
        plugin.configure(new Context("numbers", Map.of("rows", "10")));
        plugin.open();
        return plugin;
    }

    @Override
    protected String streamName() {
        return "numbers";
    }

    @Override
    protected int expectedRecordCount() {
        return 10;
    }
}
"""

SINK_PLUGIN = """\
package @PKG@;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * The {@code @NAME@} sink: appends each row a query commits to a file, one line each, its columns
 * separated by {@code |}. Append-only and at-least-once -- it claims nothing stronger, and the TCK
 * holds it to exactly that. Replace the file with your store; claim UPSERT, idempotence or
 * transactions only when they are true, and the TCK runs the cases for them.
 *
 * <p>Bound on a node as
 * <pre>
 * pravaha.sinks.totals.plugin: @NAME@
 * pravaha.sinks.totals.options.path: data/totals.txt
 * </pre>
 */
public final class @CLS@SinkPlugin implements StreamSinkPlugin {

    private Path path = Path.of("unconfigured");
    private BufferedWriter writer;

    @Override
    public String name() {
        return "@NAME@"; // what pravaha.sinks.<name>.plugin names; lower case, no spaces
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        // A missing setting is PRV-5001, naming the key and the settings that were given.
        this.path = Path.of(context.require("path"));
    }

    @Override
    public void open() {
        try {
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open " + path, e);
        }
    }

    @Override
    public void close() {
        if (writer == null) {
            return; // idempotent: the engine and the TCK may close twice
        }
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer = null;
        }
    }

    @Override
    public HealthStatus health() {
        return writer != null ? HealthStatus.healthy() : HealthStatus.degraded("not open: " + path);
    }

    @Override
    public SinkCapabilities capabilities() {
        // Must answer on an unconfigured instance: `pravaha plugins` asks before configure.
        return SinkCapabilities.appendOnly();
    }

    @Override
    public int write(List<RowView> batch) {
        try {
            for (RowView row : batch) {
                writer.write(line(row));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write to " + path, e);
        }
        return batch.size();
    }

    @Override
    public void flush() {
        try {
            writer.flush(); // what has been flushed is what a checkpoint may count on
        } catch (IOException e) {
            throw new UncheckedIOException("cannot flush " + path, e);
        }
    }

    /** One row as text: its columns joined by {@code |}, an empty field for null. */
    static String line(RowView row) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < row.schema().fieldCount(); i++) {
            if (i > 0) {
                out.append('|');
            }
            if (!row.isNull(i)) {
                out.append(value(row, i));
            }
        }
        return out.toString();
    }

    private static String value(RowView row, int i) {
        return switch (row.schema().field(i).type().typeName()) {
            case BOOLEAN -> Boolean.toString(row.getBoolean(i));
            case INT8 -> Byte.toString(row.getByte(i));
            case INT16 -> Short.toString(row.getShort(i));
            case INT32, DATE -> Integer.toString(row.getInt(i));
            case INT64, TIME, TIMESTAMP_LTZ -> Long.toString(row.getLong(i));
            case FLOAT32 -> Float.toString(row.getFloat(i));
            case FLOAT64 -> Double.toString(row.getDouble(i));
            case STRING -> row.getString(i);
            default -> throw new UnsupportedOperationException(
                    "@NAME@ does not write " + row.schema().field(i).type() + " yet");
        };
    }
}
"""

SINK_TCK = """\
package @PKG@;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.testkit.tck.SinkPluginTck;

/**
 * The sink TCK, against a file of each test's own. A case for a capability the sink does not
 * declare (upsert, retraction, idempotence, transactions) is reported skipped, not passed.
 */
class @CLS@SinkTckTest extends SinkPluginTck {

    private record Context(String instanceName, Map<String, String> config) implements PluginContext {}

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("out.txt");
    }

    @Override
    protected StreamSinkPlugin createSink() {
        // Configured and opened; the same destination each call, so a new instance is a restart.
        @CLS@SinkPlugin sink = new @CLS@SinkPlugin();
        sink.configure(new Context("out", Map.of("path", file().toString())));
        sink.open();
        return sink;
    }

    @Override
    protected StreamSchema schemaOf(StreamSinkPlugin sink) {
        // The sink writes whatever shape a query gives it, so it declares no schema; the TCK's is this.
        return StreamSchema.builder("out").field("user_id", Types.string()).field("total", Types.int64()).build();
    }

    @Override
    protected List<String> readBack() {
        try {
            return Files.exists(file()) ? Files.readAllLines(file()) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected Object[] record(int i) {
        return new Object[] {"u" + i, 100L * (i + 1)};
    }

    @Override
    protected String render(Object[] values) {
        return values[0] + "|" + values[1];
    }
}
"""

README = """\
# @ARTIFACT@

A Pravaha @KIND@ plugin, `@NAME@`, made by `pravaha plugin new`. It builds as it stands, and its
TCK test passes:

```bash
mvn verify
```

| File | What it is |
|---|---|
@TABLE@

Next: replace the example with your store, keeping the contract the TCK checks; then copy
`target/@ARTIFACT@-0.1.0-SNAPSHOT.jar` (and any client library it needs) into
`$PRAVAHA_HOME/plugins/` and bind it in the node's configuration (see the class's Javadoc). After a
restart `pravaha plugins` lists it. The whole contract is docs/development/guides/CONNECTOR_DEVELOPMENT.md.
"""


def build(name: str, kind: str, package: str, version: str, target: pathlib.Path,
          version_given: bool = False) -> Scaffold:
    cls = _camel(name)
    values = {"PKG": package, "CLS": cls, "NAME": name, "KIND": kind, "VERSION": version,
              "ARTIFACT": f"pravaha-plugin-{name}",
              "CONST": re.sub(r"[^A-Z0-9]+", "_", name.upper())}
    src = "src/main/java/" + package.replace(".", "/")
    test = "src/test/java/" + package.replace(".", "/")
    spi = "StreamSourcePlugin" if kind == "source" else "StreamSinkPlugin"
    scaffold = Scaffold(f"a Pravaha {kind} plugin, {name}")
    scaffold.add("pom.xml", _substitute(POM, values),
                 f"pravaha-api {version} (provided) and pravaha-testkit (test); Java 21")
    if kind == "source":
        scaffold.add(f"{src}/{cls}SourcePlugin.java", _substitute(SOURCE_PLUGIN, values),
                     "the plugin: name, version, configure (validates), capabilities, schema, "
                     "partitions, health")
        scaffold.add(f"{src}/{cls}Reader.java", _substitute(SOURCE_READER, values),
                     "the reader: non-blocking poll, replayable position, pause and resume")
        scaffold.add(f"{test}/{cls}SourceTckTest.java", _substitute(SOURCE_TCK, values),
                     "SourcePluginTck: the ten conformance tests every shipped source runs")
    else:
        scaffold.add(f"{src}/{cls}SinkPlugin.java", _substitute(SINK_PLUGIN, values),
                     "the plugin: appends rows to a file (option path); append-only, at-least-once")
        scaffold.add(f"{test}/{cls}SinkTckTest.java", _substitute(SINK_TCK, values),
                     "SinkPluginTck: the cases for what the sink declares; the rest skip")
    scaffold.add(f"src/main/resources/META-INF/services/{SPI}.{spi}",
                 f"{package}.{cls}{kind.capitalize()}Plugin\n",
                 "ServiceLoader registration: how a node finds the plugin on its classpath")
    table = "\n".join(f"| `{f.path}` | {f.why} |" for f in scaffold.files)
    scaffold.add("README.md", _substitute(README, values).replace("@TABLE@", table),
                 "how to build it, and where the jar goes")
    scaffold.choices = [
        f"pravaha-api and pravaha-testkit {version}: "
        + ("as --pravaha-version said" if version_given
           else "this CLI's own version (--pravaha-version for another)"),
        "pravaha-api at provided scope: the node carries it, and a second copy in plugins/ would clash",
        "maven.compiler.release 21: production runs Java 21, which cannot load newer class files",
        f"the TCK wired in ({'SourcePluginTck' if kind == 'source' else 'SinkPluginTck'}), so a "
        "change that breaks the contract fails `mvn verify`",
    ]
    scaffold.next = [f"cd {target}", "mvn verify   # builds the jar and runs the TCK",
                     f"cp target/pravaha-plugin-{name}-0.1.0-SNAPSHOT.jar $PRAVAHA_HOME/plugins/"]
    scaffold.extra = {"plugin": {"name": name, "kind": kind, "package": package,
                                 "class": f"{package}.{cls}{kind.capitalize()}Plugin",
                                 "pravahaVersion": version}}
    return scaffold


def _version(ctx: Context) -> str:
    given = ctx.arg("pravaha_version")
    if given:
        return str(given)
    version = pravaha.__version__
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?", version):
        raise UsageError(f"this CLI's own version is {version!r}, which is not an engine release; "
                         "pass --pravaha-version (the engine's, e.g. 2.3.1)")
    return version


def plugin(ctx: Context) -> int:
    name = str(ctx.arg("plugin_name") or "")
    if not re.fullmatch(r"[a-z][a-z0-9-]*", name):
        raise UsageError(f"the plugin's name {name!r} is what configuration names it by: lower case "
                         "letters, digits and hyphens, starting with a letter (e.g. my-store)")
    kind = ctx.require("kind")
    package = ctx.arg("package") or "com.example." + re.sub(r"[^a-z0-9]", "", name)
    if not re.fullmatch(r"[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)*", package):
        raise UsageError(f"--package {package!r} is not a Java package name (com.example.mystore)")
    target = pathlib.Path(ctx.arg("dir") or f"pravaha-plugin-{name}")
    return emit(ctx, target, build(name, kind, package, _version(ctx), target,
                                   bool(ctx.arg("pravaha_version"))))
