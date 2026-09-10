# Benchmarks and baselines

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential; see `../LICENSE`.

`baselines/` holds committed JMH results. CI compares each run against them and **fails the build on
a regression greater than 10 %**. Improving a baseline requires an explicit commit that updates the
recorded value, so every performance change is deliberate and reviewed.

This is set up before there is much to measure, on purpose. Retrofitting performance gates onto an
existing codebase does not work: by the time anyone notices, the regressions are already in and
nobody knows which commit caused them (implementation plan §5.2).

## Running

```bash
./mvnw -Pbench -pl pravaha-benchmarks test               # wiring check only, fast
./mvnw -Pbench -pl pravaha-benchmarks package
java -jar pravaha-benchmarks/target/benchmarks.jar -rf json -rff benchmarks/results/run.json
```

Add `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED` to include the Agrona arm; without it
`AgronaMemoryAccess.isAvailable()` is false and that arm is skipped (design §4.6).

## Reading the numbers

The reference hardware in the design is 16 physical cores at ≥ 3.0 GHz. Baselines recorded on a
developer workstation are useful as **relative regression detectors** and are not SLO evidence:
throughput numbers are meaningful, but p99.9 and p99.99 on a shared machine are noise. Absolute SLO
validation (design §5.2) needs dedicated hardware and is a Wave 3 activity.

## Files

| File | What it records |
|---|---|
| `memory-access.json` | `MemoryAccessBenchmark` — per-accessor cost for each `MemoryAccess` implementation |

## Profile A — generated versus interpreted

Design §28.4's Profile A shape: filter and project over twelve fields, ~10 % selectivity,
512-row batches. Measured on a 24-core workstation, JDK 21, generational ZGC, 2 forks.

| Arm | batches/s | rows/s | error |
|---|---|---|---|
| Generated (fused) | 570 510 | **292 M** | ±5 % |
| Interpreted | 55 971 | 28.7 M | ±28 % |

**Roughly 10×**, and at least 8× taking the interpreted arm's upper bound. That is the number
that decides whether whole-stage generation earns the risk it carries (R2). It does.

### What this is not

**It is not the Profile A gate figure.** The gate (design §5.2) is 1.2 M records per second per
*lane*, which includes source decode, arena management, ring handoff and sink dispatch. This
benchmark measures the fused operator alone, over rows already materialised in a warm region, on
fixed data that is perfectly branch-predicted. The real pipeline number will be far lower, and
claiming this as the gate would be dishonest.

What it does establish is the *ratio*, which is what the code-generation decision turns on, and both
arms process identical rows and are checked for identical output in setup.

### Two real defects this benchmark found

Neither would have been visible in a unit test.

1. **`setMemory` wrote one byte at a time.** It is called once per row to clear a header and null
   bitmap, so it cost ~50 iterations per row and was the dominant term. Now writes eight bytes at a
   time, with a regression test covering every tail length.
2. **The interpreted string comparison allocated a `String` per row**, and `And`/`Or` allocated an
   iterator per row. Both are now allocation-free. The hot path allocates nothing, and "nothing" has
   to include what the language does on your behalf.

The second matters beyond the number: fixing it made the comparison *fair*, so the two arms now
differ in dispatch cost rather than in algorithm.
