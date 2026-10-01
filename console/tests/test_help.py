"""The help system: the catalog, every page, every link, every example, every setting.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

A help page is what somebody copies from at the moment they do not know better, so the checks
here are about the page being *true*, not about it existing:

* the catalog -- a topic whose category does not exist is a page no card links to; a screen, a
  footer or a code range that names a topic that does not exist is a link to a 404;
* every page renders, and every internal link on every help page -- topics, guides, the code
  browser, decision records, About -- resolves, anchors included;
* every SQL example is marked for what it is; pravaha-it's ``HelpExamplesSqlTest`` then plans
  each one against the real engine (``console/content/examples/*.properties`` declare the
  streams and views), so here the marking and the fixture are checked, and there the SQL;
* every ``pravaha.*`` setting a page names exists in the engine's shipped application.yaml,
  and every connector option a binding example uses is one its plugin reads;
* every PRV code the engine can raise is explained on an errors topic, and every code a page
  mentions links to its own page;
* search finds things, and the gate: help and About stay public and render nothing sensitive.

The browser half -- axe on the index, a topic, a connector page and About in both themes, their
visual baselines, and the search → topic → related → full reference journey -- is in the
``test_browser_*`` files with everything else that needs Chrome.
"""
from __future__ import annotations

import pathlib
import re
import sys
from html.parser import HTMLParser
from urllib.parse import urlsplit

import pytest
import yaml

fastapi_testclient = pytest.importorskip("fastapi.testclient")

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = CONSOLE_ROOT.parent
sys.path.insert(0, str(CONSOLE_ROOT))

from fake_engine import FakeEngine
from fake_identity import ADMIN_PASSWORD, sign_in

from core.config.properties_configurator import PropertiesConfigurator
from core.content.codes import every_code
from core.content.frontmatter import FENCE
from core.content.library import ContentLibrary
from core.help_catalog import (
    CATEGORIES,
    ERROR_FAMILIES,
    MOVED,
    SCREEN_CARDS,
    SCREEN_HELP,
    TOPIC_AREA,
    HelpCatalog,
)
from run_pravaha_web import create_app

SESSION_SECRET = "help-test-session-secret-never-in-a-page"
TOPICS_DIR = CONSOLE_ROOT / "content" / "topics"
EXAMPLES_DIR = CONSOLE_ROOT / "content" / "examples"
REQUIRED = ("title", "slug", "category", "order", "icon", "summary")


@pytest.fixture(scope="module")
def anonymous():
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("console.session_secret", SESSION_SECRET)
    return fastapi_testclient.TestClient(create_app(config, engine=FakeEngine()), follow_redirects=False)


@pytest.fixture(scope="module")
def catalog() -> HelpCatalog:
    return HelpCatalog(ContentLibrary(CONSOLE_ROOT / "content"))


def _topic_files() -> list[pathlib.Path]:
    return sorted(TOPICS_DIR.glob("*.md"))


def _meta(path: pathlib.Path) -> dict:
    match = FENCE.match(path.read_text(encoding="utf-8"))
    assert match, f"{path.name} has no front matter"
    meta = yaml.safe_load(match.group(1))
    assert isinstance(meta, dict), f"{path.name}'s front matter is not a mapping"
    return meta


# ================================================================== the catalog

def test_there_is_a_substantial_help_system(catalog):
    topics = catalog.topics()
    assert len(topics) >= 60, f"only {len(topics)} topics"
    assert all(catalog.topics_in(c.id) for c in CATEGORIES), "a category has no topics"


def test_every_content_file_has_front_matter_that_parses():
    # A summary with a colon in it made one guide's whole front matter invalid YAML; the page
    # rendered with no title and nothing said so. Now the build says so.
    for area in ("help", "tutorials", "about", TOPIC_AREA):
        for path in sorted((CONSOLE_ROOT / "content" / area).glob("*.md")):
            _meta(path)


def test_every_topic_declares_what_the_catalog_needs(catalog):
    ids = {c.id for c in CATEGORIES}
    slugs: dict[str, str] = {}
    for path in _topic_files():
        meta = _meta(path)
        missing = [k for k in REQUIRED if not meta.get(k)]
        assert not missing, f"{path.name} lacks {missing}"
        assert meta["slug"] == path.stem, f"{path.name}: the slug must be the file's name"
        # A category that does not exist is a page no card links to.
        assert meta["category"] in ids, f"{path.name} names category {meta['category']!r}"
        assert meta["slug"] not in slugs, f"{meta['slug']} is claimed by {slugs.get(meta['slug'])} too"
        slugs[meta["slug"]] = path.name
        assert len(str(meta["summary"])) <= 260, f"{path.name}: a card's summary is a sentence, not a page"
        text = path.read_text(encoding="utf-8")
        prose = re.sub(r"^(```|~~~).*?^(```|~~~)", "", text, flags=re.MULTILINE | re.DOTALL)
        assert not re.search(r"^# ", prose, re.MULTILINE), f"{path.name}: the page's h1 is its title; start at ##"


def test_every_icon_the_help_names_is_one_the_vendored_icon_font_has(catalog):
    # The vendored Bootstrap Icons predate `rocket-takeoff`, `filetype-sql` and `database`; a name
    # it lacks renders as nothing at all, which is how three cards lost their icons unnoticed.
    css = (CONSOLE_ROOT / "web" / "static" / "vendor" / "bootstrap-icons" / "bootstrap-icons.css").read_text()
    have = set(re.findall(r"\.bi-([a-z0-9-]+)::before", css))
    used = {c.icon: f"category {c.id}" for c in CATEGORIES}
    used |= {card.icon: f"card {card.title}" for c in CATEGORIES for card in c.extras}
    library = ContentLibrary(CONSOLE_ROOT / "content")
    for area in ("help", "tutorials", "about", TOPIC_AREA):
        used |= {t.meta.get("icon", t.icon): f"{area}/{t.slug}" for t in library.topics(area)}
    missing = {icon: where for icon, where in used.items() if icon not in have}
    assert not missing, f"icons the vendored font does not have: {missing}"


def test_every_name_the_catalog_uses_is_a_page_that_exists(catalog):
    topics = {t.slug for t in catalog.topics()}
    guides = {g.slug for g in catalog.guides()}
    for screen, entries in SCREEN_HELP.items():
        assert 1 <= len(entries) <= SCREEN_CARDS, f"screen {screen} offers {len(entries)} cards"
        for entry in entries:
            slug, _, anchor = entry.partition("#")
            assert slug in topics, f"screen {screen} offers help topic {slug}, which does not exist"
            if anchor:
                ids = {h["id"] for h in catalog.topic(slug).headings}
                assert anchor in ids, f"screen {screen} offers {entry}, and {slug} has no such section"
        assert len(catalog.for_screen(screen)) == len(entries), f"screen {screen} loses a card"
    for digit, (_label, slug) in ERROR_FAMILIES.items():
        assert slug in topics, f"PRV-{digit}xxx is explained by {slug}, which does not exist"
    for category in CATEGORIES:
        if category.guide:
            assert category.guide.split("#")[0] in guides, f"{category.id}'s guide {category.guide}"
        for card in category.extras:
            if card.kind == "guide":
                assert card.slug in guides, f"{category.id} carries guide {card.slug}, which is not served"
    for topic in catalog.topics():
        for slug in topic.meta.get("related") or []:
            assert slug in topics, f"{topic.slug} names related topic {slug}, which does not exist"
        if topic.meta.get("guide"):
            assert str(topic.meta["guide"]).split("#")[0] in guides, f"{topic.slug}'s guide {topic.meta['guide']}"
        assert catalog.companion(topic), f"{topic.slug} has no full reference"


def test_every_topic_is_a_card_on_the_index_or_on_its_list_and_every_card_opens(anonymous, catalog):
    # One connector's page, one range of codes: listed on its list page (the Sources, Sinks and
    # Errors cards), not a card of its own. Everything else is a card.
    index = anonymous.get("/help")
    assert index.status_code == 200
    hrefs = set(re.findall(r'class="help-card" href="([^"]+)"', index.text))
    listed = 0
    for topic in catalog.topics():
        lister = topic.meta.get("listed_on")
        if lister:
            listed += 1
            assert catalog.topic(lister) is not None, f"{topic.slug} is listed on {lister}, which does not exist"
            assert f"/help/topics/{lister}" in hrefs, f"{topic.slug}'s list page {lister} has no card"
            assert f"/help/topics/{topic.slug}" not in hrefs, f"{topic.slug} is on its list and a card too"
            assert f'href="/help/topics/{topic.slug}"' in anonymous.get(f"/help/topics/{lister}").text, \
                f"{lister} does not list {topic.slug}: nobody can find it"
        else:
            assert f"/help/topics/{topic.slug}" in hrefs, f"{topic.slug} has no card: nobody can find it"
    assert listed >= 20, "the connector and error-range pages are listed, not carded"
    for href in hrefs:
        assert anonymous.get(href).status_code == 200, f"the card {href} does not open"


def test_the_index_is_a_page_somebody_can_scan(anonymous):
    cards = re.findall(r'class="help-card" href="/help/topics/', anonymous.get("/help").text)
    assert len(cards) <= 60, f"{len(cards)} topic cards on the index"


def test_every_retired_topic_redirects_to_where_its_content_is(anonymous, catalog):
    from core.help_catalog import MOVED

    assert len(MOVED) >= 20
    for old, target in MOVED.items():
        assert catalog.topic(old) is None, f"{old} is retired and still a topic"
        answer = anonymous.get(f"/help/topics/{old}")
        assert answer.status_code == 301, old
        assert answer.headers["location"] == f"/help/topics/{target}", old
        slug, _, anchor = target.partition("#")
        page = anonymous.get(f"/help/topics/{slug}")
        assert page.status_code == 200, target
        if anchor:
            assert anchor in _parse(page.text).ids, f"{old} moved to {target}, and {slug} has no #{anchor}"


def test_every_topic_renders_with_its_footer(anonymous, catalog):
    for topic in catalog.topics():
        page = anonymous.get(f"/help/topics/{topic.slug}")
        assert page.status_code == 200, topic.slug
        assert "Traceback" not in page.text
        assert "help-companion" in page.text, f"{topic.slug} has no 'Full reference' link"
        assert f"<h1>{topic.title}</h1>".replace("&", "&amp;") in page.text or topic.title in page.text
    assert anonymous.get("/help/topics/no-such-topic").status_code == 404


# ================================================================== links

class _Links(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.hrefs: list[str] = []
        self.ids: set[str] = set()

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if attributes.get("id"):
            self.ids.add(attributes["id"])
        if tag == "a" and attributes.get("href") is not None:
            self.hrefs.append(attributes["href"])


def _parse(html: str) -> _Links:
    parser = _Links()
    parser.feed(html)
    return parser


def _help_pages(catalog) -> list[str]:
    pages = ["/help", "/help/guides", "/help/codes", "/about", "/about/competitive", "/tutorials"]
    pages += [f"/help/topics/{t.slug}" for t in catalog.topics()]
    pages += [f"/help/{g.slug}" for g in catalog.guides()]
    return pages


def test_every_internal_link_on_every_help_page_resolves(anonymous, catalog):
    cache: dict[str, tuple[int, set[str]]] = {}

    def fetch(path: str) -> tuple[int, set[str]]:
        if path not in cache:
            response = anonymous.get(path)
            cache[path] = (response.status_code, _parse(response.text).ids if response.status_code == 200 else set())
        return cache[path]

    broken: list[str] = []
    for page in _help_pages(catalog):
        status, _ = fetch(page)
        assert status == 200, page
        for href in _parse(anonymous.get(page).text).hrefs:
            if href.startswith(("http://", "https://", "mailto:")):
                continue
            if href == "#" and page.startswith("/help/topics/"):
                # A relative link the renderer could not place: a topic must link to a route.
                broken.append(f"{page}: an empty link (a relative .md link?)")
                continue
            parts = urlsplit(href)
            target = parts.path or page
            if not target.startswith("/") or target.startswith("/static/"):
                continue
            if not target.startswith(("/help", "/about", "/tutorials")):
                # A product screen: behind the gate, so a redirect to sign in is the right answer.
                code = anonymous.get(target).status_code
                if code not in (200, 303, 307, 401):
                    broken.append(f"{page} -> {href} ({code})")
                continue
            retired = target.removeprefix("/help/topics/") if target.startswith("/help/topics/") else ""
            if retired in MOVED:
                # An address that answers 301. The help's own pages link to where the content is
                # now; About and the competitive page are written elsewhere, and a redirect there
                # still lands, so for them the redirect's target is what is checked.
                if not page.startswith("/about"):
                    broken.append(f"{page} -> {href} (a retired topic; link to /help/topics/{MOVED[retired]})")
                    continue
                target, _, moved_anchor = MOVED[retired].partition("#")
                target = "/help/topics/" + target
                parts = parts._replace(fragment=parts.fragment or moved_anchor)
            code, ids = fetch(target + (f"?{parts.query}" if parts.query else ""))
            if code != 200:
                broken.append(f"{page} -> {href} ({code})")
            elif parts.fragment and parts.fragment not in ids:
                broken.append(f"{page} -> {href} (no #{parts.fragment} there)")
    assert not broken, "broken links:\n  " + "\n  ".join(broken[:80])


def test_every_code_a_topic_mentions_links_to_its_page(anonymous, catalog):
    for topic in catalog.topics():
        html = anonymous.get(f"/help/topics/{topic.slug}").text
        article = html.split('<div class="card-body doc">', 1)[1].split("</article>", 1)[0]
        # Outside <pre> and outside links, no code may stand bare.
        text = re.sub(r"<pre.*?</pre>", "", article, flags=re.DOTALL)
        text = re.sub(r"<a\b.*?</a>", "", text, flags=re.DOTALL)
        text = re.sub(r"<[^>]+>", "", text)
        assert not re.search(r"\bPRV-\d{4}\b", text), f"{topic.slug} mentions a code without a link"


# ================================================================== errors

def test_every_code_the_engine_can_raise_is_explained_on_an_errors_topic(catalog):
    codes = every_code(REPO_ROOT / "docs")
    assert len(codes) > 100
    bodies = {t.slug: t.body for t in catalog.topics() if t.meta.get("category") == "errors"}
    missing = []
    for entry in codes:
        slug = ERROR_FAMILIES[entry["code"][4]][1]
        if entry["code"] not in bodies.get(slug, ""):
            missing.append(f"{entry['code']} (on {slug})")
    assert not missing, f"{len(missing)} codes are not explained where their range's page is: {missing[:30]}"


def test_the_code_browser_lists_every_code(anonymous):
    page = anonymous.get("/help/codes").text
    for entry in every_code(REPO_ROOT / "docs"):
        assert f'href="/help/codes/{entry["code"]}"' in page


# ================================================================== SQL examples

MARKER = re.compile(r"<!--\s*sql:\s*([a-z-]+)(?:\s+(PRV-\d{4}))?\s*-->\s*\n```sql")
KINDS = {"read", "refused", "read-refused", "parameterised"}


def _sql_blocks(text: str) -> list[tuple[str | None, str]]:
    blocks = []
    for match in re.finditer(r"(?:<!--\s*sql:([^>]*)-->\s*\n)?```sql[^\n]*\n(.*?)\n```", text, re.DOTALL):
        blocks.append(((match.group(1) or "").strip() or None, match.group(2)))
    return blocks


def test_every_sql_example_is_marked_for_what_it_is():
    total = 0
    for path in _topic_files():
        text = path.read_text(encoding="utf-8")
        for marker, body in _sql_blocks(text):
            total += 1
            if marker:
                kind = marker.split()[0]
                assert kind in KINDS, f"{path.name}: unknown marker 'sql: {marker}'"
            assert "..." not in body and "…" not in body, \
                f"{path.name}: an example with an ellipsis in it is not SQL anybody can run"
        # A marker must sit on the line before its block, or the Java check reads it as a
        # continuous query and the page's claim goes unchecked.
        for stray in re.finditer(r"<!--\s*sql:[^>]*-->", text):
            following = text[stray.end():].lstrip(" ").split("\n", 2)
            assert len(following) > 1 and following[1].startswith("```sql"), \
                f"{path.name}: a sql marker not directly above a ```sql block"
    assert total >= 150, f"only {total} SQL examples in the help"


def test_the_engine_side_check_reads_these_pages_and_this_fixture():
    java = (REPO_ROOT / "pravaha-it" / "src" / "test" / "java" / "com" / "ash" / "messaging" / "pravaha"
            / "it" / "HelpExamplesSqlTest.java").read_text(encoding="utf-8")
    assert '"console/content/topics"' in java
    assert '"console/content/examples"' in java
    for kind in KINDS:
        assert f'"{kind}"' in java, f"the Java check does not know the marker {kind}"


def _properties(path: pathlib.Path) -> dict[str, str]:
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            out[key.strip()] = value.strip()
    return out


def test_the_example_fixture_declares_streams_the_way_a_node_does():
    streams = _properties(EXAMPLES_DIR / "streams.properties")
    names = {k.split(".")[1] for k in streams if k.startswith("stream.")}
    assert {"txn", "orders", "trades"} <= names
    for name in names:
        assert streams[f"stream.{name}.role"] in {"source", "lookup"}
        fields = streams[f"stream.{name}.fields"]
        assert all(re.fullmatch(r"[a-z_][a-z0-9_]*:[A-Z0-9]+\??", f) for f in fields.split(",")), name
        if streams[f"stream.{name}.role"] == "source":
            assert f"{streams[f'stream.{name}.event-time']}:TIMESTAMP" in fields
    views = _properties(EXAMPLES_DIR / "views.properties")
    for key in [k for k in views if not k.endswith(".keys")]:
        assert views.get(key + ".keys"), f"{key} has no keys"


# ================================================================== settings and options

def _declared_settings() -> set[str]:
    """Every key application.yaml declares, dotted, commented ones included -- the rule
    DocumentationFreshnessTest uses -- plus every pravaha.* name a production source reads."""
    paths: set[str] = set()
    keys: list[str] = []
    indents: list[int] = []
    yaml_path = REPO_ROOT / "pravaha-server" / "src" / "main" / "resources" / "application.yaml"
    for raw in yaml_path.read_text(encoding="utf-8").split("\n"):
        line = raw.rstrip()
        if not line.strip():
            continue
        content = line.lstrip()
        indent = len(line) - len(content)
        if content.startswith("#"):
            body = content[1:]
            trimmed = body.lstrip()
            if not trimmed or trimmed.startswith("#"):
                continue
            indent += 1 + (len(body) - len(trimmed))
            content = trimmed
        match = re.match(r"^([A-Za-z0-9_.-]+):(.*)$", content)
        if not match:
            continue
        while indents and indents[-1] >= indent:
            indents.pop()
            keys.pop()
        indents.append(indent)
        keys.append(match.group(1))
        paths.add(".".join(keys))
    sources = [*REPO_ROOT.glob("*/src/main/java/**/*.java"), *REPO_ROOT.glob("plugins/*/src/main/java/**/*.java")]
    for source in sources:
        text = source.read_text(encoding="utf-8")
        paths |= set(re.findall(r'"(pravaha\.[a-z0-9.-]+[a-z0-9])"', text))
        paths |= set(re.findall(r'\$\{(pravaha\.[a-z0-9.-]+[a-z0-9])', text))
        paths |= set(re.findall(r"\{@code (pravaha\.[a-z0-9.-]*[a-z0-9])", text))
        # A @ConfigurationProperties class binds each of its fields under its prefix, relaxed:
        # `auditReaders` is `pravaha.security.audit-readers`. A Map field's keys are the operator's.
        # `ignoreUnknownFields = false` may follow the prefix (identity, tenancy).
        prefix = re.search(r'@ConfigurationProperties\(prefix\s*=\s*"(pravaha[a-z0-9.-]*)"[,)]', text)
        if prefix:
            for kind, field in re.findall(r"^    private (?:final )?([A-Za-z<>, ?.]+?) ([a-z][A-Za-z0-9]*)\s*[=;]",
                                          text, re.MULTILINE):
                name = prefix.group(1) + "." + re.sub(r"(?<!^)([A-Z])", r"-\1", field).lower()
                paths.add(name)
                if kind.startswith("Map"):
                    MAP_SETTINGS.add(name)
    return paths


#: Settings whose children are names the operator chooses (a Map field of a properties class).
MAP_SETTINGS: set[str] = set()


#: Maps whose keys are the operator's own names, not settings.
USER_NAMED = ("pravaha.streams.", "pravaha.sources.", "pravaha.lookups.", "pravaha.sinks.")


def test_every_setting_a_topic_names_exists():
    declared = _declared_settings()
    prefixes = {k.rsplit(".", 1)[0] for k in declared}
    unknown = []
    for path in _topic_files():
        text = path.read_text(encoding="utf-8")
        for name in set(re.findall(r"`(pravaha\.[a-z][a-z0-9.-]*[a-z0-9])(?:[=:][^`]*)?`", text)):
            if name.startswith(USER_NAMED) or name.endswith(".*"):
                continue
            if any(name.startswith(m + ".") for m in MAP_SETTINGS):
                continue
            if name not in declared and name not in prefixes:
                unknown.append(f"{path.name}: {name}")
    assert not unknown, "settings no application.yaml or source declares:\n  " + "\n  ".join(unknown)


#: Where each plugin's code lives, so its option names can be read from its source.
PLUGINS = {
    "filesystem": "plugins/pravaha-plugin-filesystem",
    "feedfile": "plugins/pravaha-plugin-feedfile",
    "jdbc": "plugins/pravaha-plugin-jdbc", "jdbc-lookup": "plugins/pravaha-plugin-jdbc",
    "jdbc-sink": "plugins/pravaha-plugin-jdbc",
    "delta": "plugins/pravaha-plugin-delta", "delta-sink": "plugins/pravaha-plugin-delta",
    "iceberg-sink": "plugins/pravaha-plugin-iceberg",
    "aerospike": "plugins/pravaha-plugin-aerospike", "aerospike-lookup": "plugins/pravaha-plugin-aerospike",
    "aerospike-sink": "plugins/pravaha-plugin-aerospike",
    "cassandra": "plugins/pravaha-plugin-cassandra",
    "postgres-cdc": "plugins/pravaha-plugin-postgres-cdc",
    "mysql-cdc": "plugins/pravaha-plugin-mysql-cdc",
    "kafka-sink": "plugins/pravaha-plugin-kafka", "kafka": "plugins/pravaha-plugin-kafka",
}

#: Option prefixes a plugin passes through whole, so no literal in its code names each option:
#: kafka-sink and the kafka source hand every `kafka.<property>` to the Kafka clients, and each
#: refuses a name its client does not know.
PASS_THROUGH = {"kafka-sink": ("kafka.",), "kafka": ("kafka.",)}


def _literals(module: str) -> set[str]:
    found: set[str] = set()
    for source in (REPO_ROOT / module).glob("src/main/java/**/*.java"):
        found |= set(re.findall(r'"([a-z][a-z0-9._-]*)"', source.read_text(encoding="utf-8")))
    # Options every binding passes through the engine's own binding layer.
    for shared in ("pravaha-bindings", "pravaha-connect", "pravaha-api"):
        for source in (REPO_ROOT / shared).glob("src/main/java/**/*.java"):
            found |= set(re.findall(r'"([a-z][a-z0-9._-]*)"', source.read_text(encoding="utf-8")))
    return found


def _yaml_blocks(text: str) -> list[str]:
    return [m.group(1) for m in re.finditer(r"```ya?ml[^\n]*\n(.*?)\n```", text, re.DOTALL)]


def test_every_yaml_example_parses_and_every_option_is_one_its_plugin_reads():
    literals: dict[str, set[str]] = {}
    problems = []
    blocks = 0
    for path in _topic_files():
        for block in _yaml_blocks(path.read_text(encoding="utf-8")):
            blocks += 1
            try:
                doc = yaml.safe_load(block)
            except yaml.YAMLError as exc:
                problems.append(f"{path.name}: not YAML ({str(exc).splitlines()[0]})")
                continue
            pravaha = (doc or {}).get("pravaha") if isinstance(doc, dict) else None
            if not isinstance(pravaha, dict):
                continue
            for section in ("sources", "lookups", "sinks"):
                for name, binding in (pravaha.get(section) or {}).items():
                    if not isinstance(binding, dict):
                        continue
                    plugin = binding.get("plugin")
                    if plugin not in PLUGINS:
                        problems.append(f"{path.name}: {section}.{name} names plugin {plugin!r}, which does not ship")
                        continue
                    known = literals.setdefault(plugin, _literals(PLUGINS[plugin]))
                    for option in (binding.get("options") or {}):
                        if option.startswith(PASS_THROUGH.get(plugin, ())):
                            continue
                        if option not in known:
                            problems.append(f"{path.name}: {plugin} does not read an option called {option!r}")
    assert blocks >= 20, f"only {blocks} YAML examples"
    assert not problems, "\n  ".join(problems)


# ================================================================== search

def test_search_finds_the_page_about_a_thing_first(anonymous):
    page = anonymous.get("/help/search?q=watermark").text
    results = re.findall(r'<a class="fw-semibold" href="([^"]+)"', page)
    assert results, "nothing found for 'watermark'"
    assert results[0] == "/help/topics/event-time-watermarks", results[:5]


def test_search_takes_a_code_straight_to_its_page(anonymous):
    page = anonymous.get("/help/search?q=PRV-2050").text
    results = re.findall(r'<a class="fw-semibold" href="([^"]+)"', page)
    assert results[0] == "/help/codes/PRV-2050"


def test_search_needs_every_word_and_says_when_nothing_matched(anonymous):
    page = anonymous.get("/help/search?q=jdbc+sink+transactional").text
    results = re.findall(r'<a class="fw-semibold" href="([^"]+)"', page)
    assert "/help/topics/sink-jdbc" in results[:3], results[:5]
    nothing = anonymous.get("/help/search?q=zzqxnotaword").text
    assert "Nothing matched" in nothing


def test_the_index_filters_in_the_browser_from_what_each_card_carries(anonymous):
    page = anonymous.get("/help").text
    assert 'id="help-search"' in page and 'action="/help/search"' in page
    assert "/static/js/help.js" in page
    # A card carries its headings and codes, so a filter for a code or a section title finds it.
    assert re.search(r'data-search="[^"]*prv-2050', page)


# ================================================================== the gate

PUBLIC = ["/help", "/help/search?q=checkpoint", "/help/guides", "/help/codes", "/help/codes/PRV-2050",
          "/help/decisions/043-how-a-continuous-query-names-its-sink", "/about", "/help/topics/getting-started",
          "/help/topics/source-jdbc", "/help/concepts", "/about/competitive"]


@pytest.mark.parametrize("path", PUBLIC)
def test_help_and_about_are_public_and_render_nothing_secret(anonymous, path):
    response = anonymous.get(path)
    assert response.status_code == 200, path
    assert SESSION_SECRET not in response.text
    assert ADMIN_PASSWORD not in response.text
    # A public page asks the engine nothing that would open a session for its visitor.
    assert "set-cookie" not in {k.lower() for k in response.headers}, path


def test_about_says_the_engine_version_and_nothing_else_about_the_node(anonymous):
    """ADR-052: the engine answers its status to a signed-in caller only, so a visitor learns the
    engine is up and a signed-in person learns what it runs -- and nobody the node's id."""
    page = anonymous.get("/about").text
    assert "0.1.0" not in page.split('id="engine-status"')[1].split("</dd>")[0]
    assert "engine up" in page.split('id="engine-status"')[1].split("</dd>")[0]
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("console.session_secret", SESSION_SECRET)
    signed = fastapi_testclient.TestClient(create_app(config, engine=FakeEngine()))
    sign_in(signed)
    page = signed.get("/about").text
    assert "0.1.0" in page and "RUNNING" in page
    # The fake engine's instance id and plugin list are for the plugins screen.
    assert ">n1<" not in page and "instanceId" not in page


def test_the_product_screens_stay_behind_the_gate(anonymous):
    for path in ["/workbench", "/catalog", "/views", "/operations", "/queries", "/admin/audit", "/plugins"]:
        assert anonymous.get(path).status_code in (303, 307, 401), path


# ================================================================== contextual help

SCREENS = {"/workbench": "workbench", "/catalog": "catalog", "/views": "views",
           "/views/big_txn": "view", "/views/big_txn/live": "live", "/operations": "operations",
           "/queries": "queries", "/queries/big_txn": "query", "/plugins": "plugins",
           "/admin/access": "admin", "/admin/audit": "admin", "/catalog/streams/txn": "stream",
           "/overview": "overview", "/queries/big_txn/dead-letters": "dead-letters",
           "/queries/big_txn/replacement": "replacement", "/alerts": "alerts",
           "/admin/lanes": "admin-lanes", "/admin/grants": "admin-grants",
           "/admin/policies": "admin-policies", "/admin/users": "admin-users",
           "/admin/keys": "admin-keys", "/admin/sessions": "admin-sessions",
           "/admin/ai-models": "ai-models"}


def test_every_screen_a_template_names_has_help_and_every_screen_with_help_is_named():
    # A "?" naming a screen SCREEN_HELP does not have renders nothing at all -- which is how the
    # catalog object page's two question marks were blank.
    named: set[str] = set()
    for template in (CONSOLE_ROOT / "web" / "templates").glob("*.html"):
        text = template.read_text(encoding="utf-8")
        named |= set(re.findall(r"(?:helplink|screenhelp)\('([a-z-]+)'\)", text))
        # The admin screens' shared head asks for admin-<tab>, falling back to admin.
        if "admin_head(" in text and template.name != "_admin_head.html":
            named |= {f"admin-{tab}" for tab in re.findall(r"admin_head\('([a-z-]+)'", text)}
    missing = sorted(s for s in named if s not in SCREEN_HELP and not s.startswith("admin-"))
    assert not missing, f"templates name screens with no help: {missing}"
    unused = sorted(s for s in SCREEN_HELP if s not in named)
    assert not unused, f"help declared for screens no template shows: {unused}"


def test_every_product_screen_links_to_its_help_topics(catalog):
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("console.session_secret", SESSION_SECRET)
    client = fastapi_testclient.TestClient(create_app(config, engine=FakeEngine()))
    sign_in(client)
    for path, screen in SCREENS.items():
        page = client.get(path).text
        first = SCREEN_HELP[screen][0]
        assert f'class="screen-help" href="/help/topics/{first}"' in page, f"{path} has no '?' to {first}"
        for slug in SCREEN_HELP[screen]:
            assert f'href="/help/topics/{slug}"' in page, f"{path} does not offer {slug}"


# ================================================================== the competitive landscape

def _landscape():
    from core.competitive import BEHIND, SHINE, Landscape
    from core.content.renderer import MarkdownRenderer

    land = Landscape(REPO_ROOT, MarkdownRenderer())
    return land, land.cards(SHINE), land.cards(BEHIND)


def test_every_row_of_the_competitive_table_has_its_card_and_every_card_its_row():
    # docs/publications/COMPETITIVE_LANDSCAPE.md is the one source for the table, the cards and the About
    # page's condensed version. A row with no card is a score nobody explains; a card with no
    # row, or under the wrong heading for its score, is a claim the table does not make.
    land, shine, behind = _landscape()
    columns, rows = land.table()
    assert columns and columns[-1] == "Pravaha", columns
    assert len(rows) >= 12, f"only {len(rows)} capabilities scored"
    for row in rows:
        assert len(row["scores"]) == len(columns), row["capability"]
        words = {s["word"] for s in row["scores"]}
        assert words <= {"Yes", "Partial", "No"}, f"{row['capability']}: {words}"
    orphans = [c["title"] for c in shine + behind if not c["row"]]
    assert not orphans, f"cards whose title is no row of the table: {orphans}"
    pravaha = {r["id"]: r["pravaha"] for r in rows}
    assert sorted(c["id"] for c in shine) == sorted(i for i, s in pravaha.items() if s == "Yes"), \
        "'Where Pravaha shines' must hold exactly the rows Pravaha scores Yes"
    assert sorted(c["id"] for c in behind) == sorted(i for i, s in pravaha.items() if s != "Yes"), \
        "'Where Pravaha is partial or behind' must hold exactly the rows it does not"
    for c in shine:
        assert c["why"], f"{c['title']} has no 'Why it matters' for the About page"
        assert "/help/decisions/" in c["html"] or "/help/topics/" in c["html"], \
            f"{c['title']} links to no decision record or help topic that proves it"


def test_the_competitive_page_is_public_and_both_about_and_help_lead_to_it(anonymous):
    _land, shine, behind = _landscape()
    page = anonymous.get("/about/competitive")
    assert page.status_code == 200
    for card in shine + behind:
        assert f'id="{card["id"]}"' in page.text, f"no card for {card['title']}"
    for path in ("/about", "/help"):
        assert 'href="/about/competitive"' in anonymous.get(path).text, f"{path} does not lead to it"
    about = anonymous.get("/about").text
    for card in shine:
        assert f'href="/about/competitive#{card["id"]}"' in about, card["title"]
