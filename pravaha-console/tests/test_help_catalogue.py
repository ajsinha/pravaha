"""The help catalogue (config/help.yaml), F1, and the examples gallery -- drishti's design.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

drishti keeps its help centre in one catalogue (``help.yaml``: categories, guides, ``moved:``,
``contextual:``) and lists its examples as triples a reader can open as a copy; so does this console.
Here: the catalogue is what help.yaml says and nothing else; every guide and topic is reachable; a
retired address answers 301; F1 lands on the screen's help; and every example is complete, its data
the shape of the streams it names, and opening one in the Workbench prefills the editor and registers
nothing. pravaha-it's ``HelpExamplesSqlTest`` plans each example's SQL against the real engine.
"""
from __future__ import annotations

import html
import json
import pathlib
import re
import sys

import pytest
import yaml

fastapi_testclient = pytest.importorskip("fastapi.testclient")

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONSOLE_ROOT))

from fake_engine import FakeEngine
from fake_identity import sign_in

from core.config.properties_configurator import PropertiesConfigurator
from core.content.examples import Examples
from core.content.library import ContentLibrary
from core.help_catalog import CATALOGUE, CATEGORIES, MOVED, SCREEN_HELP, HelpCatalog
from run_pravaha_web import create_app

EXAMPLES = CONSOLE_ROOT / "content" / "examples"


def _client(engine: FakeEngine | None = None):
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("console.session_secret", "help-catalogue-test-secret-not-in-a-page")
    return fastapi_testclient.TestClient(create_app(config, engine=engine or FakeEngine()), follow_redirects=False)


@pytest.fixture(scope="module")
def anonymous():
    return _client()


@pytest.fixture(scope="module")
def catalog() -> HelpCatalog:
    return HelpCatalog(ContentLibrary(CONSOLE_ROOT / "content"))


# ================================================================== the catalogue

def test_the_catalogue_is_help_yaml_and_has_drishtis_five_categories():
    data = yaml.safe_load(CATALOGUE.read_text(encoding="utf-8"))
    assert [c["id"] for c in data["categories"]] == ["start", "use", "build", "operate", "ai"]
    assert [c.name for c in CATEGORIES] == ["Start", "Use", "Build", "Operate", "AI"]
    assert MOVED == {str(k): str(v) for k, v in data["moved"].items()}
    assert SCREEN_HELP == {k: list(v) for k, v in data["contextual"].items()}
    kinds = {card.type for c in CATEGORIES for card in c.extras}
    assert kinds <= {"tutorial", "reference", "guide", "examples"} and {"tutorial", "reference"} <= kinds


def test_every_guide_and_every_topic_is_reachable_from_the_index(anonymous, catalog):
    index = anonymous.get("/help").text
    guides_page = anonymous.get("/help/guides").text
    for guide in catalog.guides():
        href = f'href="/help/{guide.slug}"'
        assert href in index or href in guides_page, f"guide {guide.slug} is reachable from nowhere"
    for topic in catalog.topics():
        lister = topic.meta.get("listed_on") or topic.slug
        assert f'href="/help/topics/{lister}"' in index, f"topic {topic.slug} is reachable from nowhere"


def test_each_category_is_a_drishti_tile_with_its_count_kind_badges_and_cards(anonymous):
    page = anonymous.get("/help").text
    hero = page.split('class="help-hero"', 1)[1].split('class="help-toolbar"', 1)[0]
    assert "<kbd>F1</kbd>" in hero and 'class="btn-pill btn-accent"' in hero
    for c in CATEGORIES:
        section = page.split(f'id="cat-{c.id}"', 1)[1].split("</details>", 1)[0]
        cards = re.findall(r'class="help-card" href="([^"]+)"', section)
        assert cards, c.id
        assert re.search(rf'href="#cat-{c.id}"[^>]*>.*?<span class="n">{len(cards)}</span>', page, re.S), c.id
        assert section.count('class="badge-kind"') == len(cards)
    assert "data-help-filter" in page and "data-help-fulltext" in page


def test_a_retired_topic_answers_301_to_its_new_home(anonymous):
    for old, new in MOVED.items():
        answer = anonymous.get(f"/help/topics/{old}")
        assert answer.status_code == 301 and answer.headers["location"] == f"/help/topics/{new}"


# ================================================================== F1

def test_f1_opens_the_help_the_screen_names_first(anonymous):
    answer = anonymous.get("/help/context/workbench")
    assert answer.status_code == 303 and answer.headers["location"] == "/help/topics/sql-reference"
    start = anonymous.get("/help/context/start")
    assert start.headers["location"] == "/help/topics/getting-started#your-first-maintained-view"
    assert anonymous.get("/help/context/no-such-screen").headers["location"] == "/help"


def test_every_page_carries_its_screen_and_the_f1_handler_from_a_file():
    client = _client()
    sign_in(client)
    page = client.get("/workbench").text
    assert 'data-screen="workbench"' in page
    assert '<script src="/static/js/help-keys.js' in page
    keys = (CONSOLE_ROOT / "web" / "static" / "js" / "help-keys.js").read_text(encoding="utf-8")
    assert '"F1"' in keys and "/help/context/" in keys
    theme = (CONSOLE_ROOT / "web" / "static" / "js" / "theme.js").read_text(encoding="utf-8")
    assert "[data-page-help]" in theme and "/help/context/" in theme


# ================================================================== examples

def _streams() -> dict[str, set[str]]:
    fields: dict[str, set[str]] = {}
    for line in (EXAMPLES / "streams.properties").read_text(encoding="utf-8").splitlines():
        match = re.match(r"stream\.([a-z_]+)\.fields=(.*)$", line.strip())
        if match:
            fields[match.group(1)] = {f.split(":")[0] for f in match.group(2).split(",")}
    return fields


def test_the_gallery_covers_what_it_says_and_every_example_is_a_triple():
    names = Examples(EXAMPLES).names()
    assert len(names) >= 12, names
    sql = " ".join(Examples(EXAMPLES).get(n).sql for n in names)
    for needed in ("TUMBLE(", "HOP(", "JOIN shipments", "FOR SYSTEM_TIME AS OF", "CREATE ALERT", "HAVING",
                   "crm_customers", "WRITING TO", "-- sql: read"):
        assert needed in sql, f"no example shows {needed}"
    on_disk = {p.stem for p in EXAMPLES.glob("*.sql")}
    assert on_disk == set(names), "an example without its note or its data is not served"
    assert Examples(EXAMPLES).get("../streams") is None and Examples(EXAMPLES).get("nope") is None


def test_every_examples_data_is_the_shape_of_the_streams_it_names():
    streams = _streams()
    for e in Examples(EXAMPLES).all():
        assert e.data_kind == "json"
        data = json.loads(e.data)
        assert data["streams"], e.name
        for stream, rows in data["streams"].items():
            assert stream in streams, f"{e.name} names stream {stream}, which the fixture does not declare"
            assert re.search(rf"\b{stream}\b", e.sql), f"{e.name}'s data is for {stream}, which its SQL never reads"
            for row in rows:
                extra = set(row) - streams[stream] - {"weight"}
                assert not extra, f"{e.name}: {stream} has no {sorted(extra)}"
                if "weight" in row:
                    assert row["weight"] in (1, -1)


def test_the_gallery_page_shows_each_example_with_its_disclosures_and_the_copy_action(anonymous):
    page = anonymous.get("/help/examples")
    assert page.status_code == 200
    for e in Examples(EXAMPLES).all():
        section = page.text.split(f'id="{e.name}"', 1)[1].split("</section>", 1)[0]
        assert f"<summary>{e.name}.sql</summary>" in section and f"<summary>{e.name}.json</summary>" in section
        assert 'action="/workbench"' in section and f'name="example" value="{e.name}"' in section
        assert "data-copy" in section and html.escape(e.title).replace("&#x27;", "&#39;") in page.text
        assert "<h1>" not in section, "the note's own title is the section's heading"


def test_open_as_a_copy_prefills_the_workbench_and_registers_nothing():
    engine = FakeEngine()
    client = _client(engine)
    sign_in(client)
    before = len(engine.registered)
    page = client.get("/workbench?example=spend-per-minute").text
    editor = page.split('id="sql"', 1)[1].split("</textarea>", 1)[0]
    assert "CREATE CONTINUOUS QUERY spend_per_minute" in editor and "Copyright" not in editor
    assert len(engine.registered) == before
    plain = client.get("/workbench?example=../../etc/passwd").text
    assert "CREATE CONTINUOUS QUERY" not in plain.split('id="sql"', 1)[1].split("</textarea>", 1)[0]
