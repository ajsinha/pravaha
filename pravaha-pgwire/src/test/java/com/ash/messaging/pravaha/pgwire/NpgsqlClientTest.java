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
package com.ash.messaging.pravaha.pgwire;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The gateway driven by a real Npgsql 4.0.17 -- the driver inside Power BI's PostgreSQL connector --
 * through the C# probe under {@code src/test/dotnet/NpgsqlProbe}.
 *
 * <p><strong>Optional by construction.</strong> It needs a {@code dotnet} SDK (on {@code PATH}, at
 * {@code $DOTNET}, or at {@code ~/.dotnet/dotnet}) and either network access to nuget.org or Npgsql
 * already in the local NuGet cache. Without either, every test here is <em>skipped</em>, with the
 * reason, never failed: the Java-side tests ({@code PowerBiCatalogTest}, {@code PgBinaryFormatTest})
 * replay the same query texts and wire formats and run everywhere. What only this class proves is
 * that the real driver agrees with them.
 *
 * <p>What it proves, in order: the connection opens with Npgsql's default type loading (three
 * catalogue queries answered in one extended-protocol batch); {@code GetSchema("Tables")} and
 * {@code GetSchema("Columns")} list the view; Power BI's own navigator queries are answered; a view
 * reads whole (Import) with every column type decoded by Npgsql's <em>binary</em> readers, which is
 * what Npgsql asks for by default; filtered, grouped and counted reads with Power BI's trailing
 * {@code LIMIT 1000001} run (DirectQuery); a top-N with {@code ORDER BY} is refused; a bound
 * parameter works; a write is refused; and a pooled connection's {@code DISCARD ALL} is accepted.
 */
class NpgsqlClientTest {

    private static final String TOKEN = "pbi-token";
    private static final Principal ANALYST = new Principal("ann", "public", Set.of("analyst"), Map.of());

    private static Path dotnet;
    private static Path probe;
    private static String skipReason;

    private PravahaPgWireServer server;

    @TempDir
    private File tlsDir;

    @BeforeAll
    static void buildProbe() throws Exception {
        dotnet = findDotnet();
        if (dotnet == null) {
            skipReason = "no dotnet SDK on PATH, at $DOTNET or at ~/.dotnet/dotnet";
            return;
        }
        Path source = Paths.get("src", "test", "dotnet", "NpgsqlProbe");
        Path work = Paths.get("target", "npgsql-probe");
        Files.createDirectories(work.resolve("src"));
        for (String file : List.of("NpgsqlProbe.csproj", "Program.cs")) {
            Files.copy(source.resolve(file), work.resolve("src").resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }
        Path out = work.resolve("out").toAbsolutePath();
        Run build = run(
                List.of(
                        dotnet.toString(),
                        "build",
                        work.resolve("src").resolve("NpgsqlProbe.csproj").toString(),
                        "-c",
                        "Release",
                        "-o",
                        out.toString(),
                        "--nologo"),
                600);
        if (build.exit != 0) {
            // A restore that cannot reach nuget.org (and has nothing cached) is an environment without
            // the driver, the same as one without dotnet: skipped. Anything else is a real failure.
            if (build.output.contains("NU1101") || build.output.contains("NU1301") || build.output.contains("NU1100")) {
                skipReason = "dotnet could not restore Npgsql 4.0.17 (offline, and not in the NuGet cache)";
                return;
            }
            throw new AssertionError("building the Npgsql probe failed:\n" + build.output);
        }
        probe = out.resolve("NpgsqlProbe.dll");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static ViewCatalog regionRevenue() {
        StreamSchema schema = StreamSchema.builder("region_revenue")
                .field("region", Types.string())
                .field("revenue", Types.int64())
                .field("orders", Types.int32())
                .field("avg_ticket", Types.decimal(10, 2))
                .field("updated_at", Types.timestamp())
                .field("day", Types.date())
                .field("active", Types.bool())
                .field("share", Types.float64().withNullable(true))
                .field("note", Types.string().withNullable(true))
                .build();
        ServedView view = new ServedView("region_revenue", schema, List.of(0), 10_000);
        // 2026-09-19T09:00:00.123456789Z: a value with sub-microsecond digits, which Npgsql's binary
        // timestamptz (microseconds) cannot carry -- the probe must see .123456, not a rounding up.
        long at = 1_789_808_400_123_456_789L;
        view.applyValues(
                new Object[] {"EMEA", 982_300L, 4410, new BigDecimal("222.74"), at, 20_715, true, 0.25, "north"},
                1,
                100);
        view.applyValues(
                new Object[] {"APAC", 611_850L, 2982, new BigDecimal("-205.18"), at, 20_715, false, null, null},
                1,
                100);
        view.applyValues(
                new Object[] {"AMER", 1_204_990L, 5120, new BigDecimal("0.05"), at, 20_715, true, 1.5, "west"}, 1, 100);
        view.commit(100);
        return new ViewCatalog().register(view);
    }

    @Test
    void powerBisDriverOpensListsImportsAndDirectQueriesAView() throws Exception {
        assumeTrue(probe != null, () -> "Npgsql probe unavailable: " + skipReason);
        server = new PravahaPgWireServer(regionRevenue())
                .authenticatedBy(StaticTokenVerifier.of(TOKEN, ANALYST))
                .start("127.0.0.1", 0);

        Map<String, String> lines = probe(server.port(), TOKEN, "disable");

        assertThat(lines.get("OK open")).startsWith("server=9.4.26").contains("sslmode=disable");
        assertThat(lines.get("OK getschema-tables")).isEqualTo("public.region_revenue:BASE TABLE");
        assertThat(lines.get("OK getschema-columns"))
                .startsWith("region:text:NO:1,revenue:int8:NO:2,orders:int4:NO:3,avg_ticket:numeric:NO:4,"
                        + "updated_at:timestamptz:NO:5,day:date:NO:6,active:bool:NO:7,share:float8:YES:8,"
                        + "note:text:YES:9");
        assertThat(lines.get("OK pbi-charsets")).isEqualTo("1 rows: character_set_name=UTF8(String)");
        assertThat(lines.get("OK pbi-tables"))
                .isEqualTo("1 rows: table_schema=public(String) table_name=region_revenue(String) "
                        + "table_type=BASE TABLE(String)");
        assertThat(lines.get("OK pbi-columns"))
                .startsWith("9 rows: column_name=region(String) ordinal_position=1(Int32) is_nullable=NO(String) "
                        + "data_type=text(String);")
                .contains("column_name=updated_at(String) ordinal_position=5(Int32) is_nullable=NO(String) "
                        + "data_type=timestamp with time zone(String)")
                .contains("column_name=avg_ticket(String) ordinal_position=4(Int32) is_nullable=NO(String) "
                        + "data_type=numeric(String)");
        assertThat(lines.get("OK pbi-foreign-keys-out")).isEqualTo("0 rows:");
        assertThat(lines.get("OK pbi-foreign-keys-in")).isEqualTo("0 rows:");
        assertThat(lines.get("OK pbi-indexes")).isEqualTo("0 rows:");

        // Every column type, through Npgsql's binary decoders.
        assertThat(lines.get("OK import-select-star"))
                .startsWith("3 rows: ")
                .contains("region=EMEA(String) revenue=982300(Int64) orders=4410(Int32) avg_ticket=222.74(Decimal) "
                        + "updated_at=2026-09-19 09:00:00.123456 Local(DateTime) day=2026-09-19 00:00:00.000000 "
                        + "Unspecified(DateTime) active=True(Boolean) share=0.25(Double) note=north(String)")
                .contains("avg_ticket=-205.18(Decimal)")
                .contains("share=NULL(DBNull) note=NULL(DBNull)")
                .contains("avg_ticket=0.05(Decimal)");
        assertThat(lines.get("OK import-power-bi-shape")).startsWith("3 rows: region=");
        assertThat(lines.get("OK navigator-preview")).startsWith("3 rows: region=");

        assertThat(lines.get("OK directquery-filter")).isEqualTo("1 rows: region=EMEA(String) revenue=982300(Int64)");
        assertThat(lines.get("OK directquery-group-by"))
                .startsWith("3 rows: ")
                .contains("region=AMER(String) a0=1204990(Int64)");
        assertThat(lines.get("OK directquery-count")).isEqualTo("1 rows: a0=3(Int64)");
        assertThat(lines.get("REFUSED directquery-top-n")).contains("PRV-2020");

        assertThat(lines.get("OK parameter"))
                .startsWith("2 rows: ")
                .contains("region=EMEA(String)")
                .contains("region=AMER(String)");
        assertThat(lines.get("REFUSED insert-refused")).isNotNull();
        assertThat(lines.get("OK pooled-reopen")).isEqualTo("1 rows: region=APAC(String)");
        assertThat(lines.keySet()).noneMatch(key -> key.startsWith("FAIL"));
    }

    @Test
    void powerBisDriverConnectsOverTlsWithTheTokenAsPassword() throws Exception {
        assumeTrue(probe != null, () -> "Npgsql probe unavailable: " + skipReason);
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(tlsDir);
        server = new PravahaPgWireServer(regionRevenue())
                .authenticatedBy(StaticTokenVerifier.of(TOKEN, ANALYST))
                .encryptedWith(cert.certificatePem, cert.privateKeyPem)
                .start("127.0.0.1", 0);

        Map<String, String> lines = probe(server.port(), TOKEN, "require");

        assertThat(lines.get("OK open")).contains("sslmode=require");
        assertThat(lines.get("OK directquery-group-by")).startsWith("3 rows: ");
        assertThat(lines.keySet()).noneMatch(key -> key.startsWith("FAIL"));
    }

    @Test
    void sslModeRequireAgainstAGatewayWithoutACertificateDoesNotConnect() throws Exception {
        assumeTrue(probe != null, () -> "Npgsql probe unavailable: " + skipReason);
        server = new PravahaPgWireServer(regionRevenue())
                .authenticatedBy(StaticTokenVerifier.of(TOKEN, ANALYST))
                .start("127.0.0.1", 0);

        Map<String, String> lines = probe(server.port(), TOKEN, "require");

        // Npgsql refuses to fall back to plaintext: the token never crosses unencrypted.
        assertThat(lines.keySet()).contains("FAIL open");
        assertThat(lines.keySet()).doesNotContain("OK open");
    }

    @Test
    void aWrongTokenIsRefusedAsAWrongPassword() throws Exception {
        assumeTrue(probe != null, () -> "Npgsql probe unavailable: " + skipReason);
        server = new PravahaPgWireServer(regionRevenue())
                .authenticatedBy(StaticTokenVerifier.of(TOKEN, ANALYST))
                .start("127.0.0.1", 0);

        Map<String, String> lines = probe(server.port(), "not-the-token", "disable");

        assertThat(lines.get("FAIL open")).startsWith("28P01");
    }

    // ------------------------------------------------------------------------------------------

    /** Runs the probe; returns each output line keyed by "STATUS name", valued by its detail. */
    private static Map<String, String> probe(int port, String password, String sslMode) throws Exception {
        Run result = run(
                List.of(
                        dotnet.toString(),
                        probe.toString(),
                        "127.0.0.1",
                        String.valueOf(port),
                        password,
                        sslMode,
                        "region_revenue"),
                180);
        Map<String, String> lines = new LinkedHashMap<>();
        for (String line : result.output.split("\n")) {
            int colon = line.indexOf(": ");
            if (colon < 0 || !(line.startsWith("OK ") || line.startsWith("FAIL ") || line.startsWith("REFUSED "))) {
                continue;
            }
            lines.put(line.substring(0, colon), line.substring(colon + 2).strip());
        }
        System.out.println("Npgsql probe output:\n" + result.output);
        return lines;
    }

    private record Run(int exit, String output) {}

    private static Run run(List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true);
        builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
        builder.environment().put("DOTNET_NOLOGO", "1");
        builder.environment().put("DOTNET_SKIP_FIRST_TIME_EXPERIENCE", "1");
        // Npgsql 4 hands a timestamptz back as a local DateTime; UTC makes "local" mean one thing.
        builder.environment().put("TZ", "UTC");
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("timed out after " + timeoutSeconds + "s: " + command);
        }
        return new Run(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    private static Path findDotnet() {
        String explicit = System.getenv("DOTNET");
        if (explicit != null && Files.isExecutable(Paths.get(explicit))) {
            return Paths.get(explicit);
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                Path candidate = Paths.get(dir, "dotnet");
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        Path userLocal = Paths.get(System.getProperty("user.home"), ".dotnet", "dotnet");
        return Files.isExecutable(userLocal) ? userLocal : null;
    }
}
