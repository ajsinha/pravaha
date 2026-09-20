"""The debugger's wire types, read back from the rows a server sends.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The step report is the only nested thing on Pravaha's control wire: three variable-length
lists laid out as counts followed by groups, because the wire is a flat list of strings
(ADR-048). Reading it wrong does not fail -- it shifts, and every field after the mistake
is the next field's value. So the rows here are written by hand, exactly as `DebugActions`
encodes them, and read back field for field.
"""
from __future__ import annotations

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from pravaha.debug import read_page, read_session, read_state, read_step, read_view


def test_a_session_reads_back_field_for_field():
    session = read_session(
        [
            "dbg-1a2b-3",
            "spend",
            "SELECT COUNT(*) AS n FROM txn",
            "4471",
            "ops@example.com",
            "2026-09-19T20:00:00Z",
            "2026-09-19T20:01:30Z",
            "7",
            "42",
            "3",
            "1740000000000000000",
            "true",
            "txn,ref",
        ]
    )
    assert session.id == "dbg-1a2b-3"
    assert session.query == "spend"
    assert session.checkpoint_id == 4471
    assert session.steps == 7
    assert session.rows_consumed == 42
    assert session.watermark_nanos == 1740000000000000000
    assert session.sinks_disabled is True
    assert session.streams == ("txn", "ref")


def test_no_watermark_is_none_rather_than_zero():
    # "Nothing has moved event time" and "event time is at the epoch" are different things,
    # and a screen that showed the second for the first would be lying about a window.
    row = ["dbg-1", "spend", "SQL", "1", "me", "t0", "t1", "0", "0", "0", "", "true", ""]
    session = read_session(row)
    assert session.watermark_nanos is None
    assert session.streams == ()


def test_a_client_that_predates_a_field_reads_the_ones_it_knows():
    # The wire is append-only: a server adds a field at the end and an older client goes on
    # reading. That promise only holds if reading past the end is not an error.
    session = read_session(["dbg-1", "spend", "SQL"])
    assert session.id == "dbg-1"
    assert session.owner == ""
    assert session.steps == 0
    assert session.sinks_disabled is False


def test_a_step_reports_its_rows_operators_and_view_changes():
    row = [
        # The eight fixed fields.
        "dbg-1", "11", "UNTIL", "1740000000000000000", "11", "3", "false", "the view satisfies total < 0",
        # One input row: stream, partition, offset, weight, event time, then its columns.
        "1", "txn", "0", "8842", "1", "1740000000000000000", "2", "user_42", "-160",
        # Three operators: id, kind, label, rows in, rows out.
        "3",
        "scan#3", "scan", "txn", "1", "1",
        "filter#2", "filter", "", "1", "1",
        "aggregate#1", "aggregate", "", "1", "2",
        # Two view changes: weight, then the columns.
        "2",
        "-1", "2", "user_42", "120",
        "1", "2", "user_42", "-40",
    ]
    step = read_step(row)
    assert step.sequence == 11
    assert step.kind == "UNTIL"
    assert step.stopped == "the view satisfies total < 0"
    assert step.exhausted is False

    assert len(step.rows_in) == 1
    assert step.rows_in[0].stream == "txn"
    assert step.rows_in[0].offset == "8842"
    assert step.rows_in[0].weight == 1
    assert step.rows_in[0].values == ("user_42", "-160")

    assert [o.id for o in step.operators] == ["scan#3", "filter#2", "aggregate#1"]
    assert step.operators[2].rows_in == 1
    assert step.operators[2].rows_out == 2

    # The retraction of the old row and the insert of the new one, in that order.
    assert [c.weight for c in step.view_changes] == [-1, 1]
    assert step.view_changes[0].values == ("user_42", "120")
    assert step.view_changes[1].values == ("user_42", "-40")


def test_a_step_that_consumed_nothing_has_empty_lists_rather_than_a_shifted_read():
    # A watermark step takes no rows. The three counts are zero and the reader must land
    # exactly at the end rather than reading the next list's count as a value.
    step = read_step(["dbg-1", "4", "WATERMARK", "500", "0", "0", "false", "event time at 500", "0", "0", "0"])
    assert step.rows_in == ()
    assert step.operators == ()
    assert step.view_changes == ()
    assert step.watermark_nanos == 500


def test_a_page_of_operator_state_keeps_the_operators_own_column_order():
    page = read_page(
        [
            "window#0", "window", "", "0", "2", "6", "2",
            "4000000000|ann", "3", "window_start", "3000000000", "window_end", "4000000000", "total", "107",
            "4000000000|bob", "3", "window_start", "3000000000", "window_end", "4000000000", "total", "11",
        ]
    )
    assert page.id == "window#0"
    assert page.key is None
    assert page.total == 6
    assert page.has_more is True
    assert [e.key for e in page.entries] == ["4000000000|ann", "4000000000|bob"]
    assert list(page.entries[0].values) == ["window_start", "window_end", "total"]
    assert page.entries[0].values["total"] == "107"


def test_the_last_page_says_there_is_no_more():
    page = read_page(["global#0", "global", "", "0", "10", "1", "1", "", "2", "n", "5", "total", "381"])
    assert page.has_more is False
    assert page.entries[0].values == {"n": "5", "total": "381"}


def test_a_state_slot_and_a_view_row():
    slot = read_state(["join#0.left", "join", "txn join ref left", "40213"])
    assert slot.id == "join#0.left"
    assert slot.entries == 40213

    row = read_view(["-1", "user_42", "120"])
    assert row.weight == -1
    assert row.values == ("user_42", "120")
