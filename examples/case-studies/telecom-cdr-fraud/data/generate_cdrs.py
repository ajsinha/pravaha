"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""Emit call detail records as CSV, one row per completed call, for the filesystem source.

    python3 data/generate_cdrs.py > data/call_records.csv
    python3 data/generate_cdrs.py --phase next >> data/call_records.csv    # 21:10 to 21:15

Five minutes of an evening, 21:00 to 21:05 UTC, on a mobile network:

  * six ordinary subscribers make one to three calls each, to domestic numbers, of a few minutes;
  * 447700900666 is a SIM box -- a rack of SIMs relaying international traffic onto the network as
    if it were local -- and makes a call every ten seconds from 21:01:00 to 21:02:50, each to a
    different number, each about half a minute long;
  * 447700900123 has been hijacked for international revenue-share fraud and makes two long calls
    to premium ranges in the Solomon Islands (SB) and a short one to Tuvalu (TV).

The last record, at 21:10:30, is an ordinary call that moves event time past every window the five
minutes open. The output is the same on every run; the numbers in the README are these.
"""
import sys
from datetime import datetime, timedelta, timezone

START = datetime(2026, 5, 14, 21, 0, 0, tzinfo=timezone.utc)
HEADER = "cdr_id,caller,callee,callee_country,duration_s,cell_id,start_time"

ORDINARY = [
    # caller, [(seconds after 21:00, callee, duration)]
    ("447700900001", [(15, "447700911001", 240), (130, "447700911002", 95), (250, "447700911003", 410)]),
    ("447700900002", [(40, "447700911004", 180), (200, "447700911005", 60)]),
    ("447700900003", [(75, "447700911006", 305)]),
    ("447700900004", [(110, "447700911007", 120)]),
    ("447700900005", [(160, "447700911008", 75)]),
    ("447700900006", [(220, "447700911009", 510)]),
]


def stamp(at: datetime) -> str:
    return at.strftime("%Y-%m-%dT%H:%M:%SZ")


def next_five_minutes() -> None:
    """21:10 to 21:15: the SIM box is moved to another cell and slows down; a subscriber calls.

    Appended while top_callers is being followed, it is that window's top three arriving as one
    commit. The last record, at 21:21:00, is what closes the window.
    """
    rows = [(640 + 50 * n, "447700900666", f"4477009230{n:02d}", "GB", 30, "cell-41") for n in range(5)]
    rows += [(660, "447700900004", "447700911011", "GB", 200), (750, "447700900004", "447700911012", "GB", 90)]
    rows = [r if len(r) == 6 else r + ("cell-12",) for r in rows]
    rows.sort(key=lambda r: (r[0], r[1]))
    for n, (offset, caller, callee, country, duration, cell) in enumerate(rows, start=1):
        print(f"cdr-n{n:02d},{caller},{callee},{country},{duration},{cell},{stamp(START + timedelta(seconds=offset))}")
    print(f"cdr-close2,447700900003,447700911013,GB,40,cell-12,{stamp(START + timedelta(minutes=21))}")


def main() -> None:
    if "--phase" in sys.argv and sys.argv[-1] == "next":
        next_five_minutes()
        return
    rows = []
    for caller, calls in ORDINARY:
        for offset, callee, duration in calls:
            rows.append((offset, caller, callee, "GB", duration, "cell-12"))
    for n in range(12):                              # the SIM box: 21:01:00 .. 21:02:50
        rows.append((60 + 10 * n, "447700900666", f"4477009220{n:02d}", "GB", 25 + (n % 4) * 5, "cell-40"))
    rows.append((185, "447700900123", "677749100", "SB", 1260, "cell-07"))
    rows.append((215, "447700900123", "68890210", "TV", 45, "cell-07"))
    rows.append((240, "447700900123", "677749101", "SB", 915, "cell-07"))
    rows.sort(key=lambda r: (r[0], r[1]))

    print(HEADER)
    for n, (offset, caller, callee, country, duration, cell) in enumerate(rows, start=1):
        print(f"cdr-{n:03d},{caller},{callee},{country},{duration},{cell},{stamp(START + timedelta(seconds=offset))}")
    # 21:10:30, less ten seconds of out-of-orderness, is a watermark of 21:10:20: past the end of
    # every window the five minutes open (the last sliding window ends at 21:09), and of the empty
    # 21:05 tumbling window. This call itself opens the 21:10 windows.
    print(f"cdr-close,447700900002,447700911010,GB,30,cell-12,{stamp(START + timedelta(minutes=10, seconds=30))}")


if __name__ == "__main__":
    main()
