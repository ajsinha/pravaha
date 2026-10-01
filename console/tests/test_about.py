"""
About and the competitive landscape, in MAYA's shape.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The pages follow MAYA's About and comparison section for section, and these tests hold the
structure rather than the prose: every section of About is there; docs/publications/COMPETITIVE_LANDSCAPE.md
has MAYA's parts (the landscape naming families and examples, the table, a note per row with
"the problem elsewhere" and a list of how Pravaha does it, what the rows have in common, the
practical reading, the dated disclaimer); the table names categories and never a vendor; the
governance comparison is its own column; the honest rows are kept; and the pages lead to each
other -- About's summary to the comparison, the comparison back to About and Help, Help's hero
to both, and the Help mega-menu to both. The row-and-note join itself is held in test_help.py.
"""
from __future__ import annotations

import pathlib
import re
import sys

import pytest

fastapi_testclient = pytest.importorskip("fastapi.testclient")

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = CONSOLE_ROOT.parent
sys.path.insert(0, str(CONSOLE_ROOT))

from fake_engine import FakeEngine
from fake_identity import sign_in

from core.about import DIFFERENT, LIMITS, PAPERS, READING, AboutSource
from core.competitive import BEHIND, SHINE, Landscape
from core.config.properties_configurator import PropertiesConfigurator
from core.content.renderer import MarkdownRenderer
from run_pravaha_web import create_app

SESSION_SECRET = "about-test-session-secret-never-in-a-page"

#: Products the landscape's prose may name as examples of a category, and the table never may.
VENDORS = ["Flink", "Spark", "Arroyo", "Materialize", "RisingWave", "Feldera", "ksqlDB", "Kafka Streams",
           "Timely", "Differential", "Hazelcast", "Unity", "Polaris", "Lake Formation", "Databricks",
           "Confluent", "Debezium", "Trino", "Pinot", "Druid", "ClickHouse"]


def _client(signed: bool = False):
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("console.session_secret", SESSION_SECRET)
    client = fastapi_testclient.TestClient(create_app(config, engine=FakeEngine()), follow_redirects=False)
    if signed:
        sign_in(client)
    return client


@pytest.fixture(scope="module")
def anonymous():
    return _client()


@pytest.fixture(scope="module")
def land() -> Landscape:
    return Landscape(REPO_ROOT, MarkdownRenderer())


def _table(html: str) -> str:
    match = re.search(r'<table class="compete".*?</table>', html, re.DOTALL)
    assert match, "no scored table on the page"
    return match.group(0)


# ================================================================== the document

def test_the_document_has_mayas_parts(land):
    columns, rows = land.table()
    assert columns[-1] == "Pravaha" and "Governance catalogues" in columns, columns
    assert len(columns) == 7, columns
    assert len(rows) >= 26, f"only {len(rows)} capabilities"
    intro = " ".join(land.landscape().split())
    # The families, each with well-known examples, and the pattern.
    for example in ("Materialize", "RisingWave", "Flink SQL", "ksqlDB", "Kafka Streams",
                    "Differential Dataflow", "DBSP", "Unity Catalog", "Polaris"):
        assert example in intro, f"the landscape does not name {example}"
    assert "The pattern is consistent" in intro
    assert land.common() and len(land.common()) >= 3
    assert "Pravaha" in land.reading()
    assert "September 2026" in land.disclaimer() and "Categories, not vendors" in land.disclaimer()


def test_every_note_has_the_problem_elsewhere_and_a_list_of_how(land):
    for note in land.cards(SHINE) + land.cards(BEHIND):
        assert note["problem"], f"{note['title']}: no 'The problem elsewhere.'"
        assert len(note["how"]) >= 1, f"{note['title']}: no list under 'How Pravaha does it.'"
        assert note["why"], f"{note['title']}: no 'Why it matters.'"
        assert note["icon"] != "stars", f"{note['title']}: no icon chosen in core/competitive.ICONS"


def test_the_table_names_categories_and_never_a_vendor(land):
    columns, rows = land.table()
    for cell in columns + [r["capability"] for r in rows]:
        for vendor in VENDORS:
            assert vendor.lower() not in cell.lower(), f"the table names {vendor}: {cell!r}"


NEW_ROWS = {
    "a-governed-catalogue-of-live-answers": "Yes",
    "alerts-that-fire-and-clear": "Yes",
    "queries-on-queries": "Partial",
    "plain-english-to-continuous-sql-with-the-engine-as-judge": "Yes",
    "bi-tools-over-the-postgresql-protocol-with-security-applied": "Yes",
    "a-lane-of-its-own-or-a-shared-one-changed-without-loss": "Yes",
    "native-change-data-capture": "Yes",
    "delta-and-iceberg-table-sinks": "Partial",
    "observability-built-in": "Yes",
    # the honest ones
    "scale-out-and-ha-maturity": "No",
    "mfa-and-single-sign-on": "No",
    "a-managed-cloud-service": "No",
    "governing-many-engines-and-data-at-rest": "No",
    "sql-breadth": "Partial",
}


def test_the_rows_asked_for_are_there_with_their_scores(land):
    _, rows = land.table()
    scores = {r["id"]: r["pravaha"] for r in rows}
    for ident, score in NEW_ROWS.items():
        assert scores.get(ident) == score, f"{ident}: {scores.get(ident)} (expected {score})"
    notes = {n["id"]: n for n in land.cards(BEHIND)}
    assert "12 of Nexmark" in " ".join(notes["sql-breadth"]["how"])
    assert "28–42 %" in " ".join(notes["scale-out-and-ha-maturity"]["how"])


def test_the_governance_column_is_scored_on_every_row(land):
    columns, rows = land.table()
    at = columns.index("Governance catalogues")
    by_id = {r["id"]: r["scores"][at]["word"] for r in rows}
    assert by_id["governing-many-engines-and-data-at-rest"] == "Yes"
    assert by_id["a-governed-catalogue-of-live-answers"] == "Partial"


# ================================================================== the competitive page

def test_the_competitive_page_is_mayas(anonymous, land):
    page = anonymous.get("/about/competitive")
    assert page.status_code == 200
    html = page.text
    hero = html.split('class="about-hero"', 1)[1].split("</section>", 1)[0]
    assert 'href="/about"' in hero and 'href="/help"' in hero, "the hero leads back to About and Help"
    for part in ('id="landscape"', 'id="shine"', 'id="behind"', 'id="reading"', 'id="disclaimer"',
                 "The pattern is consistent", "The problem elsewhere.", "How Pravaha does it.",
                 "What the rows have in common", "The practical reading", "September 2026"):
        assert part in html, f"the comparison has no {part}"
    table = _table(html)
    _, rows = land.table()
    for r in rows:
        assert f'href="#{r["id"]}"' in table, f"{r['capability']} does not link to its note"
    for word in ("cmp cmp-yes", "cmp cmp-partial", "cmp cmp-no"):
        assert word in table
    visible = re.sub(r"<[^>]+>", " ", table)
    for vendor in VENDORS:
        assert vendor not in visible, f"the rendered table names {vendor}"


# ================================================================== About

ABOUT_SECTIONS = ["what", "problem", "different", "how", "built", "release", "limits", "numbers",
                  "landscape", "principles", "reading", "stack", "author", "install"]


def test_about_has_every_section_in_mayas_order(anonymous):
    html = anonymous.get("/about").text
    positions = []
    for ident in ABOUT_SECTIONS:
        marker = f'id="{ident}"'
        assert marker in html, f"About has no #{ident}"
        positions.append(html.index(marker))
    assert positions == sorted(positions), "About's sections are out of MAYA's order"
    hero = html.split('class="about-hero"', 1)[1].split("</section>", 1)[0]
    assert "Ask once. Answer always." in hero and "Continuous SQL where your data already lives" in hero
    assert "version-badge" in hero and "pravaha-mark-white.svg" in hero
    assert "Ashutosh Sinha" in html.split('id="author"', 1)[1]


def test_about_says_what_makes_it_different_and_where_it_is_held(anonymous):
    html = anonymous.get("/about").text
    for feature in DIFFERENT:
        assert feature.title in html and f'href="{feature.href}"' in html, feature.title
    for title in ("Exact cuts", "Exact seams", "Lossless cutover", "Governed live answers", "Alerts that clear",
                  "Queries on queries", "Refuses rather than guesses", "Any model, the engine as judge"):
        assert title in [f.title for f in DIFFERENT], title
    for limit in LIMITS:
        assert limit.replace("'", "&#39;") in html or limit in html


def test_abouts_competitive_summary_leads_to_the_comparison(anonymous, land):
    html = anonymous.get("/about").text
    summary = html.split('id="landscape"', 1)[1].split("</section>", 1)[0]
    assert 'class="btn btn-sm btn-primary mt-1" href="/about/competitive"' in summary
    _, rows = land.table()
    table = _table(summary)
    for r in rows:
        assert f'href="/about/competitive#{r["id"]}"' in table, r["capability"]
    assert "Where Pravaha shines" in summary and "Where it is partial or behind" in summary


def test_the_help_index_hero_carries_about_and_the_comparison(anonymous):
    html = anonymous.get("/help").text
    hero = html.split('class="help-hero"', 1)[1].split('class="help-toolbar"', 1)[0]
    assert 'href="/about"' in hero and 'href="/about/competitive"' in hero


def test_the_help_mega_menu_carries_about_and_the_comparison():
    html = _client(signed=True).get("/overview").text
    items = set(re.findall(r'class="mega-item[^"]*" href="([^"]+)"', html))
    assert "/about" in items and "/about/competitive" in items


# ================================================================== further reading

def test_the_paper_and_the_deck_are_served_and_nothing_else(anonymous):
    source = AboutSource(REPO_ROOT, MarkdownRenderer())
    readings = {r["title"]: r for r in source.readings()}
    for r in READING:
        entry = readings[r.title]
        if r.key and (REPO_ROOT / r.path).is_file():
            assert entry["href"] == f"/about/papers/{r.key}"
            response = anonymous.get(entry["href"])
            assert response.status_code == 200, r.key
            assert response.headers["content-type"].startswith(PAPERS[r.key][1])
            assert response.content == (REPO_ROOT / r.path).read_bytes()
        else:
            assert entry["href"] == ""
    about = anonymous.get("/about").text
    assert "docs/publications/medium/pravaha-medium-post.md" in about, "the post is named by its repository path"
    for name in ("README.md", "..%2F..%2FLICENSE", "pravaha-medium-post.md", "nothing.pdf"):
        assert anonymous.get(f"/about/papers/{name}").status_code == 404, name
