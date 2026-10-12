# The quote in force at each fill: an interval join on a price stream

A best-execution check: was each fill inside the spread quoted just before it? The join keeps a
symbol's quotes for five seconds of event time and pairs each trade with those in its window.

**What the data does.** Fill 201 (09:00:10, 15030) meets the quote at 09:00:07 (15020/15030): one row.
The quote at 09:00:01 is more than five seconds older and joins nothing.

`KEYED BY (trade_id, quoted_at)`: a fill with three quotes in its window is three rows, which a read
over the view can reduce (`MAX(quoted_at)` per trade) — see [Joins](/help/topics/joins#temporal-joins)
for joining a *table* as of each row's time instead.
