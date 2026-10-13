"""Tutorial 2 -- Windows, event time and late data (docs/tutorials/02-windows-and-late-data.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Two smart meters' power readings, out-of-orderness 10 s and allowed lateness 2 minutes: a tumbling and a
hopping window, the session window that has no SQL yet, a reading that closes a window, a late one
that corrects it (-1 then +1), one too late to count, and the sink that refuses a revising query.
"""

from tutorial_captures import Background, Cli, Http, Join, Node, Open, Sh, Snap, Until

DOC = "02-windows-and-late-data.md"

NODE = Node(files=(
    ("t02/streams", "conf/streams"),
    ("t02/sinks", "conf/sinks"),
    ("t02/readings.csv", "data/incoming/readings.csv"),
    ("t02/meter_minute.sql", "meter_minute.sql"),
    ("t02/meter_rolling.sql", "meter_rolling.sql"),
))

SESSION = ("SELECT meter_id, window_start, window_end, COUNT(*) AS readings "
           "FROM TABLE(SESSION(TABLE readings, DESCRIPTOR(event_time), INTERVAL '30' SECOND)) "
           "GROUP BY meter_id, window_start, window_end")
INCOMING = '"$PRAVAHA_HOME/data/incoming/readings.csv"'


def append(capture: str, line: str) -> Sh:
    return Sh(capture, f"echo '{line}' >> data/incoming/readings.csv", show=f"echo '{line}' >> {INCOMING}")


def _plan(answer):
    """The query's own clock, from its plan: what the tutorial reads off it."""
    query = answer.get("query", answer)
    return {k: query.get(k) for k in ("name", "state", "watermark", "frontier") if k in query}


STEPS = (
    Cli("t02-describe", ("streams", "describe", "readings")),
    Sh("t02-log", "grep -oE 'stream readings:.*' logs/pravaha-server.log",
       show="grep -oE 'stream readings:.*' \"$PRAVAHA_HOME/logs/pravaha-server.log\""),
    Open("/catalog/streams/readings"),
    Snap("01-stream"),
    Cli("t02-register-minute", ("register", "--name", "meter_minute", "--sql-file", "meter_minute.sql", "--keys", "0,1")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM meter_minute"), r"^4\s*$"),
    Cli("t02-query-minute", ("query", "--sql", "SELECT meter_id, window_start, readings, min_w, max_w FROM meter_minute")),
    Http("t02-plan", "GET", "/api/v1/queries/meter_minute/plan", keep=_plan),
    Cli("t02-register-rolling", ("register", "--name", "meter_rolling", "--sql-file", "meter_rolling.sql", "--keys", "0,1")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM meter_rolling"), r"^8\s*$"),
    Cli("t02-query-rolling", ("query", "--sql", "SELECT window_start, window_end, readings, max_w FROM meter_rolling "
                                                "WHERE meter_id = 'meter-1'")),
    Cli("t02-session-refused", ("validate", "--sql", SESSION)),
    Background("close", Cli(None, ("subscribe", "--view", "meter_minute", "--limit", "2"))),
    Open("/views/meter_minute/live", "document.getElementById('live-state')"),
    append("t02-append-close", "meter-1,560,2026-10-01T09:03:10Z"),
    Join("close", "t02-subscribe-close"),
    Snap("02-live-close"),
    Background("correct", Cli(None, ("subscribe", "--view", "meter_minute", "--answer", "--limit", "2"))),
    Open("/views/meter_minute/live", "document.getElementById('live-state')"),
    append("t02-append-late", "meter-2,900,2026-10-01T09:01:50Z"),
    Join("correct", "t02-subscribe-correct"),
    Snap("03-live-correction"),
    Cli("t02-query-corrected", ("query", "--sql", "SELECT meter_id, window_start, readings, max_w FROM meter_minute "
                                                  "WHERE meter_id = 'meter-2'")),
    append("t02-append-move", "meter-2,240,2026-10-01T09:03:40Z"),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM meter_rolling WHERE window_end >= TIMESTAMP '2026-10-01 09:03:30'"),
          r"^[1-9]\d*\s*$"),
    append("t02-append-too-late", "meter-1,990,2026-10-01T09:00:30Z"),
    Cli("t02-query-unchanged", ("query", "--sql", "SELECT meter_id, window_start, readings, max_w FROM meter_minute "
                                                  "WHERE meter_id = 'meter-1'")),
    Cli("t02-rows-in", ("queries",)),
    Cli("t02-sink-refused", ("register", "--name", "meter_minute_log", "--sql-file", "meter_minute.sql", "--keys", "0,1",
                             "--sink", "meter_log")),
    Cli("t02-plan-state", ("plan", "meter_minute")),
    Open("/queries/meter_minute"),
    Snap("04-query"),
)
