"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""Emit machine sensor readings as CSV, one row per reading, for the filesystem source.

    python3 data/generate_readings.py > data/sensor_readings.csv          # the shift
    python3 data/generate_readings.py --phase late >> data/sensor_readings.csv

Three machines on two lines report every ten seconds for three minutes. press-02's temperature
climbs through the second minute and crosses the 95.0 degree alarm in the third; lathe-07 shakes for
twenty seconds in the second minute. The last row is a heartbeat from press-01 twenty seconds after
the third minute, which is what tells the engine, in event time, that all three minutes are over.

--phase late prints what arrives after that: a reading for the third minute, late but inside the
stream's allowed lateness, which corrects a window already published; one for the first minute, too
late to count, which the window drops; and a second heartbeat, which moves event time on and so
publishes the correction.

Temperatures are integer tenths of a degree (723 is 72.3 C) and vibration integer micrometres, so
nothing here is a float. The output is the same on every run: the numbers in the README are these.
"""
import argparse
from datetime import datetime, timedelta, timezone

START = datetime(2026, 3, 2, 8, 0, 0, tzinfo=timezone.utc)
HEADER = "reading_id,machine_id,line_id,temperature_dc,vibration_um,reading_time"
MACHINES = [("press-01", "L1"), ("press-02", "L1"), ("lathe-07", "L2")]


def stamp(at: datetime) -> str:
    return at.strftime("%Y-%m-%dT%H:%M:%SZ")


def temperature(machine: str, tick: int) -> int:
    if machine == "press-01":
        return 710 + (tick % 3) * 5                   # 71.0 .. 72.0, steady
    if machine == "press-02":
        if tick < 6:
            return 720 + (tick % 2) * 4               # minute 1: normal
        if tick < 12:
            return 780 + (tick - 6) * 25              # minute 2: climbing, 78.0 .. 90.5
        return 940 + (tick - 12) * 8                  # minute 3: 94.0 .. 98.0, over the alarm
    return 600 + (tick % 4) * 3                       # lathe-07 runs cool


def vibration(machine: str, tick: int) -> int:
    if machine == "lathe-07" and tick in (9, 10):     # 08:01:30 and 08:01:40
        return 180
    return 20 + (tick % 5) * 2


def shift() -> list[str]:
    rows = []
    for tick in range(18):                            # every 10 s, 08:00:00 .. 08:02:50
        at = START + timedelta(seconds=10 * tick)
        for machine, line in MACHINES:
            rows.append(f"{machine}-{tick:02d},{machine},{line},{temperature(machine, tick)},"
                        f"{vibration(machine, tick)},{stamp(at)}")
    # The heartbeat that closes the third minute: 08:03:20 minus five seconds of out-of-orderness
    # is a watermark of 08:03:15, past the end of every minute above.
    rows.append(f"press-01-hb,press-01,L1,712,21,{stamp(START + timedelta(minutes=3, seconds=20))}")
    return rows


def late() -> list[str]:
    return [
        # 08:02:55 belongs to the third minute, which closed at 08:03:00. Allowed lateness is 30 s,
        # so the minute is kept until the watermark reaches 08:03:30: this reading corrects it.
        f"press-02-late,press-02,L1,991,24,{stamp(START + timedelta(minutes=2, seconds=55))}",
        # 08:00:45 belongs to the first minute, released at 08:01:30. Dropped, and counted as late.
        f"press-01-stale,press-01,L1,999,99,{stamp(START + timedelta(seconds=45))}",
        # A correction is published when event time next moves, not the instant the late reading
        # is read: this heartbeat takes the watermark to 08:03:35, which publishes it.
        f"press-01-hb2,press-01,L1,711,20,{stamp(START + timedelta(minutes=3, seconds=40))}",
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--phase", choices=["shift", "late"], default="shift")
    args = parser.parse_args()
    if args.phase == "shift":
        print(HEADER)
        print("\n".join(shift()))
    else:
        print("\n".join(late()))


if __name__ == "__main__":
    main()
