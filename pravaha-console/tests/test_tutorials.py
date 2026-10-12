"""The hands-on tutorials (docs/tutorials/): each has what a new reader needs, and every part of it is real.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

drishti's tested format (drishti-console/tests/test_tutorials.py), held here for Pravaha's: an index
that names every tutorial; in each, a pitch, the time it takes, what to have first and what you will
learn, at least five numbered steps each ending in what you should see, next steps and a table of what
to do when something goes wrong (with the PRV codes); at least one block of output captured from a real
node (``sdk/python/tools/tutorial_captures.py``, checked by ``--check``) and three pictures made by
``tools/docs_screenshots.py --tutorial``; every relative link, anchor and picture resolves; the sample
data it names is in the repository; and nothing of the machine that wrote it -- a scratch path, a
token -- leaks into it. The console serves each tutorial at /tutorials/<slug>, pictures included, and
the old addresses answer 301.

``test_doc_images.py`` holds the pictures' bookkeeping (none missing, none left over, each one a picture
the screenshot tool makes); pravaha-it's ``TutorialSqlTest`` plans every ``sql`` block.
"""
from __future__ import annotations

import pathlib
import re

import pytest

CONSOLE = pathlib.Path(__file__).resolve().parents[1]
REPO = CONSOLE.parent
TUTORIALS = REPO / "docs" / "tutorials"
WRITTEN = sorted(TUTORIALS.glob("[0-9][0-9]-*.md"))
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)\)")
IMAGE = re.compile(r"!\[([^\]]*)\]\(([^)\s]+)\)")
HEADING = re.compile(r"^#{1,6}\s+(.*?)\s*#*\s*$", re.M)
FENCE = re.compile(r"^```.*?^```", re.M | re.S)
CAPTURE = re.compile(r"<!-- capture: (t\d\d-[a-z0-9-]+) -->\n```text\n(.*?)\n```\n<!-- /capture -->", re.S)
EXCERPT = re.compile(r"<!-- file: (data/[^ ]+) -->\n```[a-z]*\n(.*?)\n```", re.S)
#: The sections every tutorial has, in this order.
PARTS = ("**Time to complete:**", "## Before you start", "## What you will learn", "## Step 1 · ",
         "## Next steps", "## If something goes wrong")


def slug(heading: str) -> str:
    """GitHub's anchor for a heading (the documents are read there as well as in the console)."""
    s = re.sub(r"[`*_]", "", heading.strip().lower())
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


def anchors(path: pathlib.Path) -> set[str]:
    return {slug(h) for h in HEADING.findall(FENCE.sub("", path.read_text(encoding="utf-8")))}


def steps(text: str) -> list[str]:
    """Each ``## Step N · `` section, up to the next second-level heading."""
    return re.findall(r"^## Step \d+ · .*?(?=^## )", text + "\n## end", re.M | re.S)


# ================================================================== the index

def test_there_are_six_or_more_tutorials_and_the_index_names_every_one_in_order():
    assert len(WRITTEN) >= 6, [p.name for p in WRITTEN]
    numbers = [int(p.name[:2]) for p in WRITTEN]
    assert numbers == list(range(1, len(WRITTEN) + 1)), "tutorials are numbered 01, 02, ... without gaps"
    index = (TUTORIALS / "README.md").read_text(encoding="utf-8")
    table = index.split("## The tutorials", 1)[1].split("\n## ", 1)[0]
    rows = re.findall(r"^\|\s*(\d{1,2})\s*\|(.*)$", table, re.M)
    assert [int(n) for n, _ in rows[:len(WRITTEN)]] == numbers
    for path, (_, row) in zip(WRITTEN, rows):
        title = path.read_text(encoding="utf-8").splitlines()[0].split(" · ", 1)[1]
        assert f"[{title}]({path.name})" in row, f"the index row for {path.name} does not link it by its title"
        assert re.search(r"\|\s*\d+ min\s*\|\s*written\s*\|\s*$", row), row
        time = re.search(r"\*\*Time to complete:\*\* about (\d+) minutes", path.read_text(encoding="utf-8"))
        assert time and f"| {time.group(1)} min |" in row, f"{path.name}: the index and the tutorial disagree on time"


def test_the_docs_index_links_to_the_tutorials():
    assert "tutorials/README.md" in (REPO / "docs" / "README.md").read_text(encoding="utf-8")


# ================================================================== the format

@pytest.mark.parametrize("doc", WRITTEN, ids=lambda p: p.name)
def test_a_tutorial_has_the_parts_a_new_reader_needs_in_order(doc):
    text = doc.read_text(encoding="utf-8")
    assert text.startswith(f"# Tutorial {int(doc.name[:2])} · "), f"{doc.name}: the title is 'Tutorial N · ...'"
    assert "Copyright ©" in text.split("\n## ", 1)[0] and "LICENSE" in text.split("\n## ", 1)[0], doc.name
    where = [text.find(part) for part in PARTS]
    assert all(w >= 0 for w in where), f"{doc.name} lacks {[p for p, w in zip(PARTS, where) if w < 0]}"
    assert where == sorted(where), f"{doc.name}: the parts are out of order"
    pitch = text.split("\n\n")[2] if len(text.split("\n\n")) > 2 else ""
    assert len(pitch) > 200 and not pitch.startswith(("#", "**")), f"{doc.name}: a paragraph pitch first"


@pytest.mark.parametrize("doc", WRITTEN, ids=lambda p: p.name)
def test_at_least_five_steps_and_each_says_what_you_should_see(doc):
    text = doc.read_text(encoding="utf-8")
    found = steps(text)
    assert len(found) >= 5, f"{doc.name} has {len(found)} steps"
    numbers = [int(re.match(r"## Step (\d+)", s).group(1)) for s in found]
    assert numbers == list(range(1, len(found) + 1)), f"{doc.name}: steps numbered {numbers}"
    for step in found:
        assert "You should see" in step, f"{doc.name}: a step without 'You should see': {step[:70]!r}"


@pytest.mark.parametrize("doc", WRITTEN, ids=lambda p: p.name)
def test_what_goes_wrong_is_a_table_with_codes(doc):
    part = doc.read_text(encoding="utf-8").split("## If something goes wrong", 1)[1]
    assert re.search(r"^\| Symptom \| What it means \| What to do \|$", part, re.M), doc.name
    rows = [r for r in part.splitlines() if r.startswith("| ") and not r.startswith("| Symptom")]
    assert len(rows) >= 5, f"{doc.name}: {len(rows)} rows"
    assert len(set(re.findall(r"PRV-\d{4}", part))) >= 2, f"{doc.name}: name the codes a reader can meet"


@pytest.mark.parametrize("doc", WRITTEN, ids=lambda p: p.name)
def test_output_is_captured_and_pictures_are_shown(doc):
    text = doc.read_text(encoding="utf-8")
    captured = CAPTURE.findall(text)
    assert len(captured) >= 1, f"{doc.name} shows no captured output"
    assert all(i.startswith(f"t{doc.name[:2]}-") for i, _ in captured), doc.name
    assert all(body.startswith(("$ ", "GET /", "POST /", "PUT /", "DELETE /")) for _, body in captured), \
        f"{doc.name}: a capture starts with its command or its request"
    shown = IMAGE.findall(text)
    assert len(shown) >= 3, f"{doc.name} shows {len(shown)} pictures"
    for alt, rel in shown:
        assert rel.startswith(f"img/t{doc.name[:2]}/"), f"{doc.name}: {rel} is not its own picture"
        assert len(alt) >= 40, f"{doc.name}: describe what {rel} shows (alt text of {len(alt)} characters)"


# ================================================================== everything resolves

@pytest.mark.parametrize("doc", sorted(TUTORIALS.glob("*.md")), ids=lambda p: p.name)
def test_every_relative_link_anchor_and_picture_resolves(doc):
    text = FENCE.sub("", doc.read_text(encoding="utf-8"))
    for target in LINK.findall(text) + [rel for _, rel in IMAGE.findall(text)]:
        if re.match(r"^(https?:|mailto:)", target):
            continue
        path, _, frag = target.partition("#")
        dest = doc if not path else (doc.parent / path).resolve()
        assert dest.exists(), f"{doc.name}: {target} does not exist"
        if frag and dest.suffix == ".md":
            assert frag in anchors(dest), f"{doc.name}: {target}: no heading gives #{frag}"


@pytest.mark.parametrize("doc", WRITTEN, ids=lambda p: p.name)
def test_every_excerpt_is_the_file_it_names(doc):
    for name, body in EXCERPT.findall(doc.read_text(encoding="utf-8")):
        source = (TUTORIALS / name).read_text(encoding="utf-8")
        assert body in source, f"{doc.name}: the block marked {name} is not an excerpt of it"


@pytest.mark.parametrize("doc", WRITTEN, ids=lambda p: p.name)
def test_the_sample_data_is_there_and_each_script_carries_the_licence(doc):
    data = TUTORIALS / "data" / f"t{doc.name[:2]}"
    assert data.is_dir() and any(data.iterdir()), f"{doc.name}: no sample data in data/{data.name}/"
    for script in data.rglob("*.py"):
        head = script.read_text(encoding="utf-8")[:400]
        assert "Copyright (c) 2026 Ashutosh Sinha" in head, f"{script.name} has no licence header"


def test_the_configuration_tutorial_one_shows_is_what_pravaha_init_writes():
    """Tutorial 1's node is the project `pravaha init` scaffolds; the YAML the tutorial shows (comments
    shortened) must be that project's streams and sources, so the two cannot drift apart."""
    yaml = pytest.importorskip("yaml")
    from pravaha.cli import _init

    shown = next(yaml.safe_load(block) for block in re.findall(r"```yaml\n(.*?)\n```", (TUTORIALS / WRITTEN[0].name)
                                                                .read_text(encoding="utf-8"), re.S)
                 if "streams:" in block)
    scaffold = yaml.safe_load(_init.application_yaml())
    assert shown["pravaha"]["streams"] == scaffold["pravaha"]["streams"]
    assert shown["pravaha"]["sources"] == scaffold["pravaha"]["sources"]


@pytest.mark.parametrize("doc", sorted(TUTORIALS.rglob("*.*")), ids=lambda p: str(p.relative_to(TUTORIALS)))
def test_nothing_of_the_machine_that_wrote_it_leaks(doc):
    if doc.suffix not in (".md", ".py", ".sql", ".yaml", ".csv", ".properties"):
        return
    text = doc.read_text(encoding="utf-8")
    assert "/tmp/" not in text and "/home/" not in text and "pravaha-captures-" not in text, doc.name
    assert "claude" not in text.lower(), doc.name
    assert not re.search(r"prv_[a-z]+_[A-Za-z0-9_-]{10,}", text), f"{doc.name} holds a token or a key"
    assert not re.search(r"(?<![\w.])127\.0\.0\.1:(?!(18080|19090|5433|17070)\b)\d{4,5}", text), \
        f"{doc.name} names a scratch node's port"


# ================================================================== the console serves them

def test_the_console_includes_each_tutorial_once_and_the_old_copies_are_gone():
    stubs = sorted((CONSOLE / "content" / "tutorials").glob("*.md"))
    included = {}
    for stub in stubs:
        match = re.search(r"^include: (\S+)$", stub.read_text(encoding="utf-8"), re.M)
        if match:
            included.setdefault(match.group(1), []).append(stub.name)
    for doc in WRITTEN:
        rel = f"docs/tutorials/{doc.name}"
        assert included.get(rel), f"no console page includes {rel}"
        assert len(included[rel]) == 1, f"{rel} is included by {included[rel]}"
    old = REPO / "docs" / "guides" / "tutorials"
    for retired in ("01-your-first-maintained-view.md", "02-following-a-view.md",
                    "03-changing-a-running-query.md", "04-investigating-an-incident.md"):
        moved = (old / retired).read_text(encoding="utf-8")
        assert "moved" in moved.lower() and "docs/tutorials" in moved.replace("../../tutorials", "docs/tutorials"), retired
        assert len(moved.splitlines()) < 20, f"{retired} is a stub pointing at the new home, not a second copy"


@pytest.fixture(scope="module")
def anonymous():
    fastapi_testclient = pytest.importorskip("fastapi.testclient")
    from fake_engine import FakeEngine

    from core.config.properties_configurator import PropertiesConfigurator
    from run_pravaha_web import create_app

    config = PropertiesConfigurator(str(CONSOLE / "config" / "application.yaml"))
    config.set("console.session_secret", "tutorials-test-secret-not-in-a-page")
    return fastapi_testclient.TestClient(create_app(config, engine=FakeEngine()), follow_redirects=False)


def _slug(doc: pathlib.Path) -> str:
    stub = next(p for p in (CONSOLE / "content" / "tutorials").glob("*.md")
                if f"include: docs/tutorials/{doc.name}" in p.read_text(encoding="utf-8"))
    found = re.search(r"^slug: (\S+)$", stub.read_text(encoding="utf-8"), re.M)
    assert found, stub.name
    return found.group(1)


@pytest.mark.parametrize("doc", WRITTEN, ids=lambda p: p.name)
def test_the_console_serves_each_tutorial_with_its_pictures(anonymous, doc):
    page = anonymous.get(f"/tutorials/{_slug(doc)}")
    assert page.status_code == 200, doc.name
    pictures = re.findall(r'<img class="tutorial-shot" alt="[^"]+" src="(/tutorials/img/t\d\d/[^"]+\.png)"', page.text)
    assert len(pictures) == len(IMAGE.findall(doc.read_text(encoding="utf-8"))), f"{doc.name}: not every picture is drawn"
    for src in pictures[:2]:
        answer = anonymous.get(src)
        assert answer.status_code == 200 and answer.headers["content-type"] == "image/png", src
    assert "Screenshot:" not in page.text, f"{doc.name}: a picture fell back to its description"


def test_only_a_tutorial_picture_is_served_from_the_picture_route(anonymous):
    for bad in ("/tutorials/img/t01/missing-name.png", "/tutorials/img/t01/99-nothing.png",
                "/tutorials/img/x01/01-sign-in.png", "/tutorials/img/t01/..%2F..%2FREADME.md"):
        assert anonymous.get(bad).status_code == 404, bad


def test_a_retired_tutorial_address_answers_301_to_one_that_exists(anonymous):
    from core.help_catalog import TUTORIALS_MOVED

    assert TUTORIALS_MOVED.get("first-maintained-view") == "first-15-minutes"
    live = {_slug(d) for d in WRITTEN}
    for old, new in TUTORIALS_MOVED.items():
        assert new in live, f"tutorials-moved: {old} -> {new}, which is no tutorial"
        answer = anonymous.get(f"/tutorials/{old}")
        assert answer.status_code == 301 and answer.headers["location"] == f"/tutorials/{new}", old
