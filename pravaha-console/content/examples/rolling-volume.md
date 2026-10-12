# Shares traded over the last five minutes, every minute: a hopping window

`HOP(..., slide, size)` opens a window every minute (the slide), each five minutes wide (the size).
The windows overlap, so each fill belongs to five of them, and a dashboard reading `rolling_volume`
by `(symbol, window_end)` sees "the last five minutes" move forward one minute at a time.

**What the data does.** Fill 101 (09:00:10) is in the five windows ending 09:01 to 09:05. Once the
watermark (5 s behind the stream) passes 09:05, the window `[09:00, 09:05)` has published `ACME`:
2 fills, 140 shares and `GLOBX`: 1 fill, 500 shares. Fill 104 is in no window that also holds 101.

A hopping window costs `size / slide` times the state of a tumbling one; [Windows](/help/topics/windows)
has the arithmetic.
