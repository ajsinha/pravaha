# Late data: what the watermark waits for, and what it drops

`readings` declares `out-of-orderness: 1m`: the watermark trails the newest event time seen by a
minute, and a window is published only when the watermark passes its end. A row inside that minute
is **not late** — it is counted. A row behind the watermark is **late**, and with the default allowed
lateness of zero it is **dropped**; the published answer stands.

**What the data does**, in arrival order:

| Row | Event time | Watermark after | Effect |
|---|---|---|---|
| d-1, battery 90 | 09:01:00 | 09:00:00 | counted in `[09:00, 09:05)` |
| d-2, battery 75 | 09:04:10 | 09:03:10 | counted |
| d-1, battery 89 | 09:05:40 | 09:04:40 | the next window; `[09:00, 09:05)` still open |
| d-3, no battery figure | 09:04:50 | 09:04:40 | out of order but **not late**: counted (`MIN` skips the null) |
| d-1, battery 88 | 09:06:30 | 09:05:30 | `[09:00, 09:05)` published: 3 readings, lowest battery 75 |
| d-2, battery 74 | 09:03:00 | 09:05:30 | **late**: its window is published; dropped, and the 75 stands |

To have the last row correct the published window instead, give the stream an allowed lateness
(`pravaha.streams.readings.allowed-lateness`) — the query then revises its answer, and needs a sink that
takes retractions. [Late data and corrections](/help/topics/event-time-watermarks#late-data) has the rest.
