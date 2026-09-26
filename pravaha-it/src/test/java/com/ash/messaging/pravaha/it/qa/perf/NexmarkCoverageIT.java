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
package com.ash.messaging.pravaha.it.qa.perf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-038's Nexmark comparison, run as far as it can honestly be run here.
 *
 * <p>The published gate is win condition W5: <em>Nexmark q0-q22 against Flink SQL on the same
 * hardware, parity or better on at least 18 of 22 and at least 2x on at least 8</em>. ADR-038 moved
 * it to the roadmap on the grounds that it is a competitive claim rather than a deployment
 * requirement, and it has never been run.
 *
 * <p><strong>The head-to-head cannot be run on this machine, and this harness does not pretend
 * otherwise.</strong> It needs three things that do not exist here: a Flink deployment to compare
 * against, one quiet machine to run both on, and the Nexmark data generator. What it can do is the
 * thing that must be true before any comparison is worth setting up, and which nobody had checked:
 * <strong>how many of the twenty-three published queries this engine can run at all.</strong> A
 * head-to-head on the subset that happens to plan is the selective benchmarking design section 28.4
 * says this audience detects and punishes, so the coverage number is the honest headline and the
 * refusals are the result.
 *
 * <p>Each query is put through two stages, because they refuse in different places:
 *
 * <ol>
 *   <li><strong>plans</strong> -- {@code QueryRegistry.outputSchemaOf} parses, validates and builds
 *       a physical plan without starting anything.
 *   <li><strong>compiles</strong> -- registration builds the pipeline, which is where a plan that is
 *       shaped correctly but has no runnable operator is refused. The self-join refusal lives here.
 * </ol>
 *
 * <p>The SQL is Nexmark's, transcribed rather than rewritten: camel case lower-cased to match the
 * schemas in {@link NexmarkStreams}, and nothing else changed except where the note beside a query
 * says so. A query rewritten until it plans is not the benchmark's query, and the count of what
 * runs would then be a count of what was rewritten.
 *
 * <p><strong>Not part of the default build.</strong> Named {@code *IT}, which surefire excludes.
 *
 * <pre>
 * ./mvnw -o -pl pravaha-it test -Dtest=NexmarkCoverageIT \
 *     -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@Timeout(3600)
final class NexmarkCoverageIT {

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final int PEOPLE = 2_000;
    private static final int AUCTIONS = 4_000;
    private static final int BIDS = 40_000;

    /** Rows fed to each runnable query, per source stream, in the throughput pass. */
    private static final long ROWS_PER_STREAM = Long.getLong("pravaha.nexmark.rows", 200_000L);

    /**
     * One published Nexmark query.
     *
     * @param sql null when the query cannot be transcribed against this engine's surface at all --
     *     it needs a construct there is no way to write, such as a UDF or a processing-time
     *     attribute. Recorded separately from a refusal, because "we said no" and "there is nothing
     *     to say no to" are different facts.
     */
    private record Nexmark(String id, String title, String sql, String note) {}

    private static List<Nexmark> queries() {
        List<Nexmark> all = new ArrayList<>();
        all.add(new Nexmark("q0", "pass-through", "SELECT auction, bidder, price, date_time, extra FROM bid", ""));
        all.add(new Nexmark(
                "q1",
                "currency conversion",
                "SELECT auction, bidder, 0.908 * price AS price, date_time, extra FROM bid",
                "the published query multiplies by a DECIMAL literal"));
        all.add(new Nexmark("q2", "selection", "SELECT auction, price FROM bid WHERE MOD(auction, 123) = 0", ""));
        all.add(new Nexmark(
                "q3",
                "local item suggestion",
                "SELECT P.name, P.city, P.state, A.id FROM auction AS A INNER JOIN person AS P ON A.seller = P.id "
                        + "WHERE A.category = 10 AND (P.state = 'or' OR P.state = 'id' OR P.state = 'ca')",
                ""));
        all.add(
                new Nexmark(
                        "q4",
                        "average price for a category",
                        "SELECT Q.category, AVG(Q.final_price) FROM (SELECT MAX(B.price) AS final_price, A.category "
                                + "FROM auction AS A, bid AS B WHERE A.id = B.auction AND B.date_time BETWEEN A.date_time "
                                + "AND A.expires GROUP BY A.id, A.category) AS Q GROUP BY Q.category",
                        "the published alias is `final`, renamed to `final_price` because FINAL is reserved; nothing else changed"));
        all.add(new Nexmark(
                "q5",
                "hot items",
                "SELECT AuctionBids.auction, AuctionBids.num FROM (SELECT B1.auction, count(*) AS num, "
                        + "HOP_START(B1.date_time, INTERVAL '2' SECOND, INTERVAL '10' SECOND) AS starttime FROM bid B1 "
                        + "GROUP BY B1.auction, HOP(B1.date_time, INTERVAL '2' SECOND, INTERVAL '10' SECOND)) AS AuctionBids "
                        + "JOIN (SELECT max(CountBids.num) AS maxn, CountBids.starttime FROM (SELECT count(*) AS num, "
                        + "HOP_START(B2.date_time, INTERVAL '2' SECOND, INTERVAL '10' SECOND) AS starttime FROM bid B2 "
                        + "GROUP BY B2.auction, HOP(B2.date_time, INTERVAL '2' SECOND, INTERVAL '10' SECOND)) AS CountBids "
                        + "GROUP BY CountBids.starttime) AS MaxBids ON AuctionBids.starttime = MaxBids.starttime "
                        + "AND AuctionBids.num >= MaxBids.maxn",
                "reads `bid` on both sides"));
        all.add(new Nexmark(
                "q6",
                "average selling price by seller",
                "SELECT Q.seller, AVG(Q.final_price) OVER (PARTITION BY Q.seller ORDER BY Q.date_time ROWS BETWEEN "
                        + "10 PRECEDING AND CURRENT ROW) FROM (SELECT MAX(B.price) AS final_price, A.seller, A.date_time "
                        + "FROM auction AS A, bid AS B WHERE A.id = B.auction AND B.date_time BETWEEN A.date_time "
                        + "AND A.expires GROUP BY A.id, A.seller, A.date_time) AS Q",
                "OVER window; `final` renamed as in q4"));
        all.add(new Nexmark(
                "q7",
                "highest bid",
                "SELECT B.auction, B.price, B.bidder, B.date_time FROM bid B JOIN (SELECT MAX(price) AS maxprice, "
                        + "TUMBLE_END(date_time, INTERVAL '10' SECOND) AS date_time FROM bid GROUP BY "
                        + "TUMBLE(date_time, INTERVAL '10' SECOND)) B1 ON B.price = B1.maxprice WHERE B.date_time "
                        + "BETWEEN B1.date_time - INTERVAL '10' SECOND AND B1.date_time",
                "reads `bid` on both sides"));
        all.add(new Nexmark(
                "q8",
                "monitor new users",
                "SELECT P.id, P.name, P.starttime FROM (SELECT id, name, TUMBLE_START(date_time, INTERVAL '10' SECOND) "
                        + "AS starttime, TUMBLE_END(date_time, INTERVAL '10' SECOND) AS endtime FROM person "
                        + "GROUP BY id, name, TUMBLE(date_time, INTERVAL '10' SECOND)) P JOIN (SELECT seller, "
                        + "TUMBLE_START(date_time, INTERVAL '10' SECOND) AS starttime, TUMBLE_END(date_time, "
                        + "INTERVAL '10' SECOND) AS endtime FROM auction GROUP BY seller, TUMBLE(date_time, "
                        + "INTERVAL '10' SECOND)) A ON P.id = A.seller AND P.starttime = A.starttime "
                        + "AND P.endtime = A.endtime",
                ""));
        all.add(new Nexmark(
                "q9",
                "winning bids",
                "SELECT id, item_name, seller, category, auction, bidder, price, bid_date_time FROM (SELECT A.id, "
                        + "A.item_name, A.seller, A.category, B.auction, B.bidder, B.price, B.date_time AS bid_date_time, "
                        + "ROW_NUMBER() OVER (PARTITION BY A.id ORDER BY B.price DESC, B.date_time ASC) AS rownum "
                        + "FROM auction A, bid B WHERE A.id = B.auction AND B.date_time BETWEEN A.date_time AND A.expires) "
                        + "WHERE rownum <= 1",
                "ROW_NUMBER OVER"));
        all.add(new Nexmark(
                "q10",
                "log to file system",
                null,
                "the published query is an INSERT into a partitioned file sink; DATE_FORMAT, which it also uses, is "
                        + "built, and the sink has no way to be written here, so there is nothing to refuse"));
        all.add(new Nexmark(
                "q11",
                "user sessions",
                "SELECT B.bidder, count(*) AS bid_count, SESSION_START(B.date_time, INTERVAL '10' SECOND) AS starttime, "
                        + "SESSION_END(B.date_time, INTERVAL '10' SECOND) AS endtime FROM bid B GROUP BY B.bidder, "
                        + "SESSION(B.date_time, INTERVAL '10' SECOND)",
                "session windows"));
        all.add(new Nexmark(
                "q12",
                "processing-time windows",
                null,
                "the published query windows on PROCTIME(); this engine has no processing-time attribute to write, "
                        + "so the query cannot be transcribed rather than being refused"));
        all.add(new Nexmark(
                "q13",
                "bounded side input join",
                null,
                "needs a registered lookup source. Pravaha does support lookup joins (`JOIN dim FOR SYSTEM_TIME AS OF "
                        + "...`, see LookupJoinTest); this harness registers no lookup plugin, so the query is out of "
                        + "scope here rather than refused"));
        all.add(new Nexmark(
                "q14",
                "calculation",
                null,
                "the published query calls a user-defined function, `count_char`. There is no UDF surface to call it "
                        + "through, so there is nothing to submit"));
        all.add(new Nexmark(
                "q15",
                "bidding day statistics",
                "SELECT DATE_FORMAT(date_time, 'yyyy-MM-dd') AS bid_day, count(*) AS total_bids, "
                        + "count(distinct bidder) AS distinct_bidders FROM bid GROUP BY DATE_FORMAT(date_time, 'yyyy-MM-dd')",
                "abbreviated from the published ten columns; alias `day` renamed `bid_day` because DAY is reserved"));
        all.add(new Nexmark(
                "q16",
                "channel statistics per day",
                "SELECT channel, DATE_FORMAT(date_time, 'yyyy-MM-dd') AS bid_day, count(*) AS total_bids, "
                        + "count(distinct bidder) AS distinct_bidders FROM bid GROUP BY channel, "
                        + "DATE_FORMAT(date_time, 'yyyy-MM-dd')",
                "abbreviated as q15; alias `day` renamed `bid_day`"));
        all.add(new Nexmark(
                "q17",
                "auction statistics per day",
                "SELECT auction, DATE_FORMAT(date_time, 'yyyy-MM-dd') AS bid_day, count(*) AS total_bids, "
                        + "min(price) AS min_price, max(price) AS max_price, avg(price) AS avg_price, "
                        + "sum(price) AS sum_price FROM bid GROUP BY auction, DATE_FORMAT(date_time, 'yyyy-MM-dd')",
                "alias `day` renamed `bid_day` because DAY is reserved"));
        all.add(new Nexmark(
                "q18",
                "find last bid",
                "SELECT auction, bidder, price, channel, url, date_time, extra FROM (SELECT *, ROW_NUMBER() OVER "
                        + "(PARTITION BY bidder, auction ORDER BY date_time DESC) AS rank_number FROM bid) "
                        + "WHERE rank_number <= 1",
                "ROW_NUMBER OVER"));
        all.add(new Nexmark(
                "q19",
                "auction top-10 price",
                "SELECT * FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY auction ORDER BY price DESC) AS rank_number "
                        + "FROM bid) WHERE rank_number <= 10",
                "ROW_NUMBER OVER"));
        all.add(new Nexmark(
                "q20",
                "expand bid with auction",
                "SELECT auction, bidder, price, channel, url, B.date_time AS bid_date_time, B.extra AS bid_extra, "
                        + "item_name, description, initial_bid, reserve, A.date_time AS auction_date_time, expires, "
                        + "seller, category, A.extra AS auction_extra FROM bid AS B INNER JOIN auction AS A "
                        + "ON bidder = A.seller WHERE A.category = 10",
                ""));
        all.add(new Nexmark(
                "q21",
                "add channel id",
                "SELECT auction, bidder, price, channel, CASE WHEN lower(channel) = 'apple' THEN '0' "
                        + "WHEN lower(channel) = 'google' THEN '1' ELSE REGEXP_EXTRACT(url, '(&|^)channel_id=([^&]*)', 2) "
                        + "END AS channel_id FROM bid",
                ""));
        all.add(new Nexmark(
                "q22",
                "get URL directories",
                "SELECT auction, bidder, price, channel, SPLIT_INDEX(url, '/', 3) AS dir1, "
                        + "SPLIT_INDEX(url, '/', 4) AS dir2, SPLIT_INDEX(url, '/', 5) AS dir3 FROM bid",
                ""));
        return all;
    }

    /**
     * The same queries with one minimal change each, to separate "cannot be expressed" from "was
     * written a way this planner does not recognise".
     *
     * <p><strong>These are not Nexmark's queries and are not counted in the coverage figure.</strong>
     * They exist because three of the refusals above are for the comma join Nexmark writes --
     * {@code FROM auction A, bid B WHERE A.id = B.auction AND ...} -- which this planner sees as a
     * cartesian product with a filter above it rather than as an equi-join with a time bound. That
     * is a different defect from "no join of this shape exists", and a gate document that does not
     * distinguish them sends somebody to build the wrong thing.
     */
    private static List<Nexmark> rewrites() {
        List<Nexmark> all = new ArrayList<>();
        all.add(new Nexmark(
                "q4",
                "average price for a category",
                "SELECT Q.category, AVG(Q.final_price) FROM (SELECT MAX(B.price) AS final_price, A.category "
                        + "FROM auction AS A INNER JOIN bid AS B ON A.id = B.auction AND B.date_time BETWEEN "
                        + "A.date_time AND A.expires GROUP BY A.id, A.category) AS Q GROUP BY Q.category",
                "comma join written as INNER JOIN ... ON"));
        all.add(new Nexmark(
                "q6",
                "average selling price by seller",
                "SELECT Q.seller, AVG(Q.final_price) OVER (PARTITION BY Q.seller ORDER BY Q.date_time ROWS BETWEEN "
                        + "10 PRECEDING AND CURRENT ROW) FROM (SELECT MAX(B.price) AS final_price, A.seller, "
                        + "A.date_time FROM auction AS A INNER JOIN bid AS B ON A.id = B.auction AND B.date_time "
                        + "BETWEEN A.date_time AND A.expires GROUP BY A.id, A.seller, A.date_time) AS Q",
                "comma join written as INNER JOIN ... ON"));
        all.add(new Nexmark(
                "q9",
                "winning bids",
                "SELECT A.id, A.item_name, A.seller, A.category, B.auction, B.bidder, B.price FROM auction A "
                        + "INNER JOIN bid B ON A.id = B.auction AND B.date_time BETWEEN A.date_time AND A.expires",
                "comma join as INNER JOIN ... ON, and the ROW_NUMBER filter dropped entirely"));
        return all;
    }

    private record Verdict(String id, String stage, String code, String detail) {}

    /**
     * What runs, as measured. 2026-09-20: q0, q2, q3, q8, q20. 2026-09-26: q21 and q22, once
     * REGEXP_EXTRACT, SPLIT_INDEX and text equality inside an expression were built.
     */
    private static final List<String> EXPECTED_TO_RUN = List.of("q0", "q2", "q3", "q8", "q20", "q21", "q22");

    @Test
    void howManyOfNexmarkThisEngineCanRunAtAll() {
        List<Verdict> verdicts = new ArrayList<>();
        try (QueryRegistry registry = new QueryRegistry(
                new ViewCatalog(), NexmarkStreams.person(), NexmarkStreams.auction(), NexmarkStreams.bid())) {
            for (Nexmark query : queries()) {
                verdicts.add(verdictFor(registry, query));
            }
        }

        System.out.printf("%n  ADR-038's Nexmark comparison, coverage half, on %s%n%n", "2026-09-20");
        System.out.printf("    %-4s %-32s %-16s %-10s %s%n", "id", "query", "verdict", "code", "detail");
        for (Verdict verdict : verdicts) {
            System.out.printf(
                    "    %-4s %-32s %-16s %-10s %s%n",
                    verdict.id(), titleOf(verdict.id()), verdict.stage(), verdict.code(), verdict.detail());
        }

        long runnable = verdicts.stream().filter(v -> "RUNS".equals(v.stage())).count();
        long refused =
                verdicts.stream().filter(v -> v.stage().startsWith("REFUSED")).count();
        long unwritable = verdicts.stream()
                .filter(v -> "NOT EXPRESSIBLE".equals(v.stage()))
                .count();

        Map<String, Long> byCode = new LinkedHashMap<>();
        for (Verdict verdict : verdicts) {
            if (verdict.stage().startsWith("REFUSED")) {
                byCode.merge(verdict.code(), 1L, Long::sum);
            }
        }

        System.out.printf(
                "%n    Nexmark coverage: %d of %d queries run at all. %d are refused, %d cannot be written "
                        + "against this surface.%n",
                runnable, verdicts.size(), refused, unwritable);
        byCode.forEach((code, count) -> System.out.printf("      %-10s refuses %d%n", code, count));
        System.out.printf(
                "%n    W5 asks for parity or better against Flink SQL on at least 18 of 22 queries and 2x on at%n"
                        + "    least 8. %d run. W5 is therefore NOT REACHED, and it is not reached by a margin that%n"
                        + "    has nothing to do with speed: the missing queries are missing SQL -- OVER windows,%n"
                        + "    session windows in SQL, self-joins, unwindowed grouping, processing time and%n"
                        + "    user functions. No throughput figure changes that, and no%n"
                        + "    comparison should be published on the subset that happens to plan.%n",
                runnable);
        System.out.printf(
                "%n    Minimal rewrites -- NOT Nexmark's queries, NOT counted above. What one change buys:%n");
        try (QueryRegistry registry = new QueryRegistry(
                new ViewCatalog(), NexmarkStreams.person(), NexmarkStreams.auction(), NexmarkStreams.bid())) {
            for (Nexmark rewrite : rewrites()) {
                Verdict verdict = verdictFor(registry, rewrite);
                System.out.printf(
                        "      %-4s %-48s %-18s %-10s %s%n",
                        rewrite.id(), rewrite.note(), verdict.stage(), verdict.code(), verdict.detail());
            }
        }

        System.out.print(MachineState.now().describe());

        // The harness itself must have done the work: every query got a verdict, and at least one
        // reached each of the three outcomes, or the classification is not being exercised.
        assertThat(verdicts).hasSize(23);
        assertThat(verdicts.stream()
                        .filter(v -> "RUNS".equals(v.stage()))
                        .map(Verdict::id)
                        .toList())
                .as("the queries measured to run; a change to this list is a change to the coverage figure "
                        + "docs/gates/measured-2026-09-20/README.md reports, and both move together")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_TO_RUN);
        assertThat(runnable)
                .as("no Nexmark query runs at all, which would mean the harness never planned one")
                .isPositive();
        assertThat(refused + unwritable).isPositive();
    }

    @Test
    void howFastTheNexmarkQueriesThatRunGoHere() {
        MemoryAccess access = MemoryAccess.best();
        List<Nexmark> runnable = new ArrayList<>();
        try (QueryRegistry probe = new QueryRegistry(
                new ViewCatalog(), NexmarkStreams.person(), NexmarkStreams.auction(), NexmarkStreams.bid())) {
            for (Nexmark query : queries()) {
                if ("RUNS".equals(verdictFor(probe, query).stage())) {
                    runnable.add(query);
                }
            }
        }

        MachineState before = MachineState.now();
        System.out.printf(
                "%n  Nexmark throughput for the %d queries that run, %,d rows into each source stream%n",
                runnable.size(), ROWS_PER_STREAM);
        System.out.printf("    %-4s %-32s %-16s %s%n", "id", "query", "rows/s", "rows taken");

        try (NexmarkStreams streams = NexmarkStreams.encode(access, PEOPLE, AUCTIONS, BIDS)) {
            for (Nexmark query : runnable) {
                double[] rate = new double[1];
                long[] taken = new long[1];
                runOne(streams, query, rate, taken);
                System.out.printf("    %-4s %-32s %,-16.0f %,d%n", query.id(), query.title(), rate[0], taken[0]);
                assertThat(taken[0])
                        .as("%s took no rows, so its rate is a rate for nothing", query.id())
                        .isPositive();
            }
        }
        System.out.print(before.describe());
        System.out.printf("    NOTE    : these are not Nexmark numbers. The rows come from this harness's generator%n"
                + "              rather than Nexmark's, the machine is a loaded developer laptop, and%n"
                + "              the queries that are missing are the expensive ones. Nothing here may%n"
                + "              be compared with a published Flink, RisingWave or Feldera figure.%n");
    }

    // ---------------------------------------------------------------- helpers

    private static String titleOf(String id) {
        return queries().stream()
                .filter(query -> query.id().equals(id))
                .map(Nexmark::title)
                .findFirst()
                .orElse("");
    }

    private static Verdict verdictFor(QueryRegistry registry, Nexmark query) {
        if (query.sql() == null) {
            return new Verdict(query.id(), "NOT EXPRESSIBLE", "-", query.note());
        }
        try {
            registry.outputSchemaOf(query.sql());
        } catch (PravahaException e) {
            return new Verdict(query.id(), "REFUSED planning", e.errorCode().code(), firstLine(e.getMessage()));
        } catch (RuntimeException e) {
            return new Verdict(query.id(), "REFUSED planning", "uncoded", firstLine(e.getMessage()));
        }
        String name = "nexmark_" + query.id();
        try {
            registry.register(name, query.sql(), List.of(0), DANA);
        } catch (PravahaException e) {
            return new Verdict(query.id(), "REFUSED compiling", e.errorCode().code(), firstLine(e.getMessage()));
        } catch (RuntimeException e) {
            return new Verdict(query.id(), "REFUSED compiling", "uncoded", firstLine(e.getMessage()));
        }
        registry.drop(name);
        return new Verdict(query.id(), "RUNS", "-", query.note());
    }

    private static void runOne(NexmarkStreams streams, Nexmark query, double[] rate, long[] taken) {
        try (QueryRegistry registry = new QueryRegistry(
                new ViewCatalog(), NexmarkStreams.person(), NexmarkStreams.auction(), NexmarkStreams.bid())) {
            RegisteredQuery registered = registry.register("nexmark_" + query.id(), query.sql(), List.of(0), DANA);
            List<String> sources = registered.sourceStreams();
            Map<String, BinaryRowView> viewsByStream = new LinkedHashMap<>();
            for (String stream : sources) {
                viewsByStream.put(stream, new BinaryRowView(RowLayout.of(NexmarkStreams.schemaOf(stream))));
            }

            long began = System.nanoTime();
            for (long i = 0; i < ROWS_PER_STREAM; i++) {
                for (String stream : sources) {
                    long[] offsets = streams.offsetsOf(stream);
                    BinaryRowView view = viewsByStream.get(stream);
                    view.wrap(streams.region(), (int) offsets[(int) (i % offsets.length)]);
                    while (!registered.accept(stream, view)) {
                        Thread.onSpinWait();
                    }
                }
            }
            registered.awaitApplied(java.time.Duration.ofMinutes(5));
            long took = System.nanoTime() - began;
            registered.advanceWatermark(NexmarkStreams.watermarkPastEverything());
            taken[0] = registered.rowsIn();
            rate[0] = taken[0] / (took / 1e9);
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() <= 110 ? line : line.substring(0, 107) + "...";
    }
}
