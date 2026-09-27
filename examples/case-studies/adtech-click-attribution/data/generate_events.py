"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""Emit ad impressions and clicks as two CSV files, for two filesystem sources.

    python3 data/generate_events.py            # writes data/impressions.csv and data/clicks.csv

Sixty impressions are served ten seconds apart from 12:00:00 UTC, rotating through three
campaigns and three placements, to twenty users. Every fourth impression is clicked, between
thirty seconds and a minute and a half after it was served.

Two clicks are there to be refused attribution: one on impression imp-008 arrives twelve minutes
after it was served, past the ten-minute attribution window; the other names an impression that
was never served (a click-fraud script guessing ids).

Each file ends with a heartbeat row at 12:25:00. The join's event time is the lesser of the two
streams', so both must move on before any window over the join can close. The output is the same
on every run.
"""
import pathlib
from datetime import datetime, timedelta, timezone

START = datetime(2026, 7, 1, 12, 0, 0, tzinfo=timezone.utc)
CAMPAIGNS = ["summer-sale", "new-phone", "travel"]
PLACEMENTS = ["news-top", "sports-side", "video-pre"]
DATA = pathlib.Path(__file__).resolve().parent


def stamp(at: datetime) -> str:
    return at.strftime("%Y-%m-%dT%H:%M:%SZ")


def main() -> None:
    impressions = ["impression_id,campaign_id,user_id,placement,cost_micros,impression_time"]
    clicks = []
    for i in range(60):
        served = START + timedelta(seconds=10 * i)
        impression, user = f"imp-{i:03d}", f"u-{i % 20:02d}"
        impressions.append(f"{impression},{CAMPAIGNS[i % 3]},{user},{PLACEMENTS[(i // 3) % 3]},"
                           f"{1500 + (i % 4) * 250},{stamp(served)}")
        if i % 4 == 0 and i != 8:
            clicks.append((served + timedelta(seconds=30 + (i % 5) * 15), impression, user))
    # Twelve minutes after imp-008 was served: outside the ten-minute window, so never attributed.
    clicks.append((START + timedelta(seconds=80, minutes=12), "imp-008", "u-08"))
    # An impression that was never served.
    clicks.append((START + timedelta(minutes=4, seconds=5), "imp-999", "u-99"))
    clicks.sort()

    impressions.append(f"imp-hb,none,u-hb,none,0,{stamp(START + timedelta(minutes=25))}")
    click_rows = ["click_id,impression_id,user_id,click_time"]
    click_rows += [f"clk-{n:03d},{imp},{user},{stamp(at)}" for n, (at, imp, user) in enumerate(clicks, start=1)]
    click_rows.append(f"clk-hb,none,u-hb,{stamp(START + timedelta(minutes=25))}")

    (DATA / "impressions.csv").write_text("\n".join(impressions) + "\n")
    (DATA / "clicks.csv").write_text("\n".join(click_rows) + "\n")
    print(f"wrote {len(impressions) - 1} impressions and {len(click_rows) - 1} clicks to {DATA}")


if __name__ == "__main__":
    main()
