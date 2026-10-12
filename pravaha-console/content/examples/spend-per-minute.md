# Spend per user per minute: a tumbling window

The engine's primary use: a windowed aggregate over a stream. `TUMBLE` cuts `txn` into one-minute
windows by **event time**; each window's row is kept until the watermark passes its end (the stream's
out-of-orderness, 10 s here) and is then published, once, and its state released.

**What the data does.** At 09:01:20 the watermark is 09:01:10, past 09:01:00, so the window
`[09:00, 09:01)` is published: `u1` 2 payments, 1550, and `u2` 1 payment, 300. The rows at 09:01:20
and 09:02:15 wait for their own windows.

**Keys.** `KEYED BY (user_id, window_end)`: one row per user per minute, so a point read asks for one
user's minute. `window_start` is in the answer and need not be in the key: the window's end says it.

See [Windows](/help/topics/windows) for hopping windows and when a window emits.
