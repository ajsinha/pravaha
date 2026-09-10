#!/usr/bin/env python3
"""Emit aligned-read QC metrics as aql INSERT statements.

    python3 generate_reads.py --run run-A --samples s-01,s-02,s-03 --minutes 5 --degrade s-02 \
      | docker exec -i pravaha-aerospike aql

--degrade names a sample whose coverage falls away after the first minute: depth drops
and it stops touching most of the panel. That is the failure the case study is about
catching while the run is still going.
"""
import argparse
import random

NANOS = 1_000_000_000
START_NANOS = 1767225600 * NANOS
TARGETS = [f"EX{n}" for n in range(1, 13)]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", default="run-A")
    parser.add_argument("--samples", default="s-01,s-02,s-03")
    parser.add_argument("--minutes", type=int, default=5)
    parser.add_argument("--rate", type=float, default=20.0, help="reads per second")
    parser.add_argument("--degrade", default=None, help="sample whose coverage collapses")
    parser.add_argument("--seed", type=int, default=3)
    args = parser.parse_args()

    random.seed(args.seed)
    samples = [s.strip() for s in args.samples.split(",") if s.strip()]
    seconds = args.minutes * 60

    read_id = 0
    for tick in range(int(seconds * args.rate)):
        elapsed = tick / args.rate
        offset = int(elapsed * NANOS)
        sample = random.choice(samples)
        failing = sample == args.degrade and elapsed > 60

        # A collapsing sample keeps producing reads -- that is why depth alone is not enough to
        # notice. What changes is how deep they are and how much of the panel they touch.
        depth = random.randint(4, 14) if failing else random.randint(35, 70)
        target = random.choice(TARGETS[:2]) if failing else random.choice(TARGETS)
        mapq = random.choice([12, 25]) if random.random() < 0.15 else random.randint(40, 60)
        read_id += 1
        print(
            "INSERT INTO test.read_metric (PK, read_id, run_id, sample_id, target_id, mapping_quality, "
            f"depth, gc_percent, called_at) VALUES ('r-{read_id}', {read_id}, '{args.run}', '{sample}', "
            f"'{target}', {mapq}, {depth}, {random.randint(35, 60)}, {START_NANOS + offset});"
        )

    closing = START_NANOS + seconds * NANOS + 60 * NANOS
    print(
        "INSERT INTO test.read_metric (PK, read_id, run_id, sample_id, target_id, mapping_quality, depth, "
        f"gc_percent, called_at) VALUES ('r-close', 0, '{args.run}', '{samples[0]}', 'EX1', 60, 50, 45, "
        f"{closing});"
    )


if __name__ == "__main__":
    main()
