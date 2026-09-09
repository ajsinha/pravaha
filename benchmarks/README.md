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
