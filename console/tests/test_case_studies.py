"""
Pravaha console -- the case studies in Help.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The owner asked for the shape MAYA has: a case-studies section on the help pages, a card per
study, and each card opening that study's README. The READMEs and the index table in
examples/case-studies/README.md are the source; what is pinned here is that every listed study is
served, that nothing in a served study is a dead link, and that a name reaches a path only once
the index table has named it.
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

from core.config.properties_configurator import PropertiesConfigurator
from core.content import case_studies
from run_pravaha_web import create_app


@pytest.fixture(scope="module")
def anonymous():
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("console.session_secret", "case-studies-test-secret")
    return fastapi_testclient.TestClient(create_app(config, engine=FakeEngine()), follow_redirects=False)


def _listed_folders() -> list[str]:
    index = (REPO_ROOT / "examples" / "case-studies" / "README.md").read_text(encoding="utf-8")
    return re.findall(r"^\|\s*\[[^\]]+\]\(([\w-]+)/?\)", index, re.M)


def test_the_catalog_is_the_index_tables_rows_in_its_order():
    studies = case_studies.catalog(REPO_ROOT)
    assert [s.slug for s in studies] == [f for f in _listed_folders()
                                         if (REPO_ROOT / "examples" / "case-studies" / f / "README.md").is_file()]
    assert len(studies) >= 5
    assert all(s.title and s.domain and s.shows and "**" not in s.shows for s in studies)


def test_the_index_is_public_and_has_a_card_for_every_study(anonymous):
    page = anonymous.get("/help/case-studies")
    assert page.status_code == 200
    for study in case_studies.catalog(REPO_ROOT):
        assert f'href="/help/case-studies/{study.slug}"' in page.text


def test_every_study_renders_with_no_dead_link_and_nothing_missing(anonymous):
    for study in case_studies.catalog(REPO_ROOT):
        page = anonymous.get(f"/help/case-studies/{study.slug}")
        assert page.status_code == 200, study.slug
        assert "not present in this installation" not in page.text
        body = page.text[page.text.find('class="card-body doc"'):page.text.find('<nav class="study-nav"')]
        # The console's renderer turns a relative link it does not know into "#"; every link a
        # README writes must reach this page already pointed somewhere real.
        assert 'href="#"' not in body, f"{study.slug} has a dead link"


def test_a_name_the_index_does_not_list_is_not_found(anonymous):
    # A bare ".." never reaches the route: the client normalises it away, to /help/.
    for name in ("nope", "..%2F..%2Fdocs", "%2E%2E", "SETUP"):
        assert anonymous.get(f"/help/case-studies/{name}").status_code == 404, name


def test_links_go_to_the_study_the_setup_page_the_console_or_the_repository():
    readme = "\n".join([
        "[sql](sql/01-a.sql) [next](../trade-processing/) [setup](../SETUP.md) [all](../README.md)",
        "[guide](../../../docs/CONTINUOUS_QUERIES.md) [licence](../../../LICENSE) [web](https://x.example/)",
        "```", "[in a fence](sql/untouched.sql)", "```",
    ])
    out = case_studies.relink(readme, "banking-card-velocity", REPO_ROOT)
    assert "](https://github.com/ajsinha/pravaha/blob/main/examples/case-studies/banking-card-velocity/sql/01-a.sql)" in out
    assert "](/help/case-studies/trade-processing)" in out
    assert "](/tutorials/setup)" in out
    assert "](/help/case-studies)" in out
    assert "](../../../docs/CONTINUOUS_QUERIES.md)" in out  # the renderer's own route for it
    assert "](https://github.com/ajsinha/pravaha/blob/main/LICENSE)" in out
    assert "](https://x.example/)" in out
    assert "[in a fence](sql/untouched.sql)" in out


def test_the_help_index_offers_the_case_studies(anonymous):
    assert 'href="/help/case-studies"' in anonymous.get("/help").text
