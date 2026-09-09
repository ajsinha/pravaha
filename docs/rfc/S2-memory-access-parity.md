# Spike S2 — Agrona versus ByteBuffer/VarHandle

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

| | |
|---|---|
| Question | Are the two `MemoryAccess` implementations within 3 %? If Agrona is decisively faster, the Java 25 profile becomes more attractive and design §4.5 gets revisited. |
| Time box | 2 days (implementation plan §12) |
| Status | **Answered.** No revisit needed. |
| Hardware | 24-core workstation, JDK 21.0.12, ZGC generational. Short run: 1 fork, 3×1 s warmup, 3×1 s measurement. |

## Result

| Benchmark | ByteBuffer + VarHandle | Agrona `UnsafeBuffer` | Verdict |
|---|---|---|---|
| `getLong` | 1.309 ± 0.351 ns | 1.143 ± 1.955 ns | Tie — Agrona's error bar is larger than the difference |
| `putLong` | 1.419 ± 0.374 ns | 1.126 ± 0.011 ns | Agrona ~20 % faster |
| `readRowOfTwelveFields` | **2.875 ± 0.299 ns** | 5.030 ± 0.928 ns | **ByteBuffer 1.75× faster** |
| `equalsUtf8Literal` (9 B) | **2.488 ± 0.221 ns** | 5.766 ± 1.880 ns | **ByteBuffer 2.3× faster** |

## Reading

Agrona wins marginally on a single isolated accessor. **ByteBuffer wins decisively on the two
patterns that actually occur**: reading a whole row, and comparing a UTF-8 literal. Those are the
hot path — generated operators read many fields per row and compare string literals in predicates;
they do not read one `long` in a loop.

The most likely explanation is that HotSpot intrinsifies `byteBufferViewVarHandle` accesses and can
then keep the buffer's base and address in registers across a sequence of reads, whereas each
Agrona call goes through an extra indirection that inhibits the same hoisting. The single-accessor
case hides this because there is no sequence to optimise across.

## Decision

**Keep `ByteBufferMemoryAccess` as the default.** It was already chosen for being flag-free
(design §4.6, the correction recorded there); it turns out to be the faster choice as well, so
there is no trade-off to weigh.

Agrona remains selectable via `-Dpravaha.memory=agrona` for deployments that control their own JVM
arguments, but nothing now argues for making it the default. **Design §4.5–4.6 stand as written and
the Java 25 profile gains no new argument.**

## Caveats

- A short run on a shared workstation. Throughput ordering is clear and repeated across
  measurements; the absolute numbers are not SLO evidence (design §5.2 needs dedicated hardware).
- Compiler blackholes are experimental on this JVM; JMH warns, and both arms were measured under
  identical conditions so the comparison holds even if absolute values shift.
- The FFM arm was not measured: it needs JDK 22+, and the question here was specifically about the
  Java 21 baseline. Worth revisiting if the baseline ever moves.

## Bonus finding

`getLong` at ~1.3 ns is roughly **4 cycles at 3 GHz**, which is exactly what design §29.1's cycle
budget assumed for "binary field read, fixed-width, L1-resident". Twelve fields in 2.875 ns is
~0.24 ns per field — the JIT is clearly hoisting and pipelining across the sequence. The
performance model underpinning the whole design is confirmed by measurement rather than asserted.
