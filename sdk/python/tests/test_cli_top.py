"""``pravaha top``: rates from two samples, one frame with ``--once``, no escape codes off a terminal,
and Ctrl-C ending it cleanly with the cursor shown again.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The node is ``test_cli``'s fake. The wait between samples is replaced: it changes what the fake
answers (the rows a query has taken in) and moves a fake clock, so a rate is exact and no test
sleeps.
"""

from __future__ import annotations

import datetime
import io
import json

import pytest

from pravaha.cli import EXIT_OK, EXIT_USAGE, main
from pravaha.cli import _top
from test_cli import answer, engine, home, run  # noqa: F401

WATERMARK = "2026-10-05T12:00:00Z"
NOW = datetime.datetime(2026, 10, 5, 12, 0, 30, tzinfo=datetime.timezone.utc)


def _query(name: str, rows_in: int, lane: str = "shared", shared_lane=0) -> dict:
    return {"name": name, "state": "RUNNING", "rowsIn": rows_in, "countsWithheld": False,
            "lane": lane, "sharedLane": shared_lane}


def _plan(held: int, subscribers: int, state_bytes=None) -> dict:
    return {"query": {"rowsIn": 0, "stateHeld": held, "stateCeiling": 1000, "viewSize": held,
                      "watermark": WATERMARK, "subscribers": subscribers},
            "operatorMetrics": {"1": {"stateBytes": state_bytes}, "2": {"stateBytes": None}}}


def _node(spend: int, clicks: int) -> None:
    answer("GET", "/api/v1/queries", [_query("spend", spend), _query("clicks", clicks, "dedicated", None)])
    answer("GET", "/api/v1/queries/spend/plan", _plan(40, 2, 2048))
    answer("GET", "/api/v1/queries/clicks/plan", _plan(7, 0))


@pytest.fixture
def clock(monkeypatch):
    """A clock that a wait moves forward, and a wait that has the fake node take in more rows."""
    state = {"t": 100.0, "waits": 0}

    def wait(seconds: float) -> None:
        state["t"] += seconds
        state["waits"] += 1
        _node(1000 + 500 * state["waits"], 30 + 2 * state["waits"])

    monkeypatch.setattr(_top, "_clock", lambda: state["t"])
    monkeypatch.setattr(_top, "_sleep", wait)
    monkeypatch.setattr(_top, "_now", lambda: NOW)
    return state


def test_once_samples_twice_and_rates_are_the_difference_over_the_interval(engine, home, clock):
    _node(1000, 30)
    code, out, err = run("top", "--once", "--json", "--http", engine, "--sort", "rate")
    assert code == EXIT_OK, err
    frame = json.loads(out)
    assert frame["intervalSeconds"] == 2.0 and frame["sort"] == "rate"
    spend, clicks = frame["queries"]  # largest rate first
    assert spend["name"] == "spend" and spend["rowsPerSecond"] == 250.0  # 500 rows over 2 s
    assert clicks["rowsPerSecond"] == 1.0
    assert spend["watermarkDelaySeconds"] == 30.0
    assert spend["stateHeld"] == 40 and spend["stateCeiling"] == 1000 and spend["stateBytes"] == 2048
    assert spend["subscribers"] == 2 and spend["lane"] == "shared 0" and clicks["lane"] == "dedicated"
    assert sorted(spend) == sorted(["name", "state", "lane", "rowsIn", "rowsPerSecond", "watermark",
                                    "watermarkDelaySeconds", "stateHeld", "stateCeiling",
                                    "stateBytes", "viewRows", "subscribers"])


def test_once_prints_one_table_and_no_escape_codes_off_a_terminal(engine, home, clock):
    _node(1000, 30)
    code, out, _ = run("top", "--once", "--http", engine, "--interval", "4")
    assert code == EXIT_OK
    assert "\033" not in out
    lines = out.splitlines()
    assert lines[0].startswith("pravaha top  ") and "2 queries" in lines[0] and "Ctrl-C" not in lines[0]
    assert lines[1].split()[:3] == ["NAME", "STATE", "LANE"] and "ROWS/S" in lines[1]
    assert [line.split()[0] for line in lines[2:]] == ["clicks", "spend"]  # by name
    assert "125.0" in lines[3] and "30.0s" in lines[3] and "40/1000" in lines[3] and "2.0 KiB" in lines[3]


def test_a_withheld_count_has_no_rate(engine, home, clock, monkeypatch):
    answer("GET", "/api/v1/queries", [{**_query("spend", -1), "countsWithheld": True}])
    answer("GET", "/api/v1/queries/spend/plan", {"query": {"stateHeld": -1, "subscribers": 1}})
    monkeypatch.setattr(_top, "_sleep", lambda s: None)  # the node answers the same both times
    code, out, _ = run("top", "--once", "--json", "--http", engine)
    query = json.loads(out)["queries"][0]
    assert code == EXIT_OK and query["rowsIn"] is None and query["rowsPerSecond"] is None
    assert query["stateHeld"] is None and query["subscribers"] == 1


class _Terminal(io.StringIO):
    def isatty(self) -> bool:
        return True


def test_live_on_a_terminal_redraws_and_ctrl_c_exits_zero_with_the_cursor_back(engine, home, clock,
                                                                                monkeypatch):
    _node(1000, 30)
    waits = {"n": 0}

    def wait(seconds: float) -> None:
        waits["n"] += 1
        if waits["n"] == 2:
            raise KeyboardInterrupt
        clock["t"] += seconds
        _node(1600, 30)

    monkeypatch.setattr(_top, "_sleep", wait)
    out, err = _Terminal(), io.StringIO()
    code = main(["top", "--http", engine, "--no-color"], stdout=out, stderr=err)
    assert code == EXIT_OK, err.getvalue()
    text = out.getvalue()
    assert text.startswith("\033[?25l") and text.endswith("\033[?25h")
    frames = text.split("\033[H\033[2J")[1:]
    assert len(frames) == 2 and "Ctrl-C" in frames[0]
    assert "300.0" in frames[1] and "300.0" not in frames[0]  # the first frame has no rate yet


def test_live_off_a_terminal_appends_plain_frames_and_json_lines(engine, home, clock, monkeypatch):
    _node(1000, 30)
    waits = {"n": 0}

    def wait(seconds: float) -> None:
        waits["n"] += 1
        if waits["n"] == 2:
            raise KeyboardInterrupt
        clock["t"] += seconds

    monkeypatch.setattr(_top, "_sleep", wait)
    code, out, _ = run("top", "--http", engine)
    assert code == EXIT_OK and "\033" not in out and out.count("pravaha top  ") == 2
    waits["n"] = 0
    code, out, _ = run("top", "--http", engine, "--json")
    frames = [json.loads(line) for line in out.splitlines()]
    assert code == EXIT_OK and len(frames) == 2 and frames[0]["queries"][0]["rowsPerSecond"] is None


def test_a_bad_interval_is_usage(home):
    assert run("top", "--interval", "0")[0] == EXIT_USAGE
    assert run("top", "--sort", "colour")[0] == EXIT_USAGE
