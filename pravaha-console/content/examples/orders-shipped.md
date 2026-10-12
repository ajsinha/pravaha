# Orders and their shipments: a time-bounded stream join

Two streams joined on a key **and** a bound on event time. The bound decides which pairs match and
how long state is kept: an order is held only until no shipment inside its day can still arrive, then
let go. A `LEFT JOIN` needs the bound (`PRV-2020`): without it there is no moment at which an order
can be declared unshipped.

**What the data does.** Order 5001 (09:00) ships at 14:30 the same day: one row,
`(5001, 9001, dhl)`. Order 5002 ships at 09:10: `(5002, 9002, ups)`. A shipment arriving more than a
day after its order joins nothing.

`KEYED BY (order_id, shipment_id)`: an order shipped in two parcels is two rows.
[Joins](/help/topics/joins) has the other shapes and the refusals.
