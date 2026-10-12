# A filtered view, and a snapshot read of it

Two halves of Pravaha in one example. The `CREATE` is a **continuous query**: it runs once, at
registration, and from then on its view is maintained as rows arrive. The `SELECT` after it is a
**read** of that view — no stream is scanned and nothing is recomputed; the answer is what the view
holds now. A read may `GROUP BY` freely, because a view is bounded; a continuous query may not group a
stream without a window ([why](/help/topics/sql-refusals)).

**What the data does.** Payments 31, 33 and 34 pass the filter. The read answers
`u1: 2 payments, 22200` and `u4: 1 payment, 1500`.

In the Workbench, *Run* executes the read against a registered `big_payments`; *Register* registers
the `CREATE`.
