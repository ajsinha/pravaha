"""The help is accurate by test, not by eye: every name a page states is checked against its source.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

``test_help.py`` checks that the help is *wired* -- every card opens, every link lands. This
file checks that what the pages *say* is so, against the thing each statement is about:

* every ``PRV-nnnn`` a page names is a code the engine declares (``new ErrorCode(n, ...)``) and
  is in the generated table the code browser lists;
* every ``pravaha.*`` setting the guides and tutorials name exists (the topics are checked in
  ``test_help.py``), every ``console.*`` key the help names is one the console reads, and every
  default a settings table states is the default the node actually has -- the shipped
  ``application.yaml``, else the ``${key:default}`` a source reads it with, else the field's
  initialiser on its ``@ConfigurationProperties`` class;
* every ``pravaha`` command, verb and flag a page shows is one the Python CLI's parser has, and
  every ``pravaha-engine`` command and flag one the Java tool reads;
* every REST call a page shows (``GET /api/v1/...``, a ``curl`` to one) is a path, and a method,
  in ``api/openapi.lock.json``;
* every ``pravaha_*`` metric a page names is one the engine or the console publishes.

The SQL examples are planned against the real engine by pravaha-it's ``HelpExamplesSqlTest``.

Scope: the topics, the tutorials, and the documents the guides include -- except the ones that are
history or design by nature (the release notes, the roadmap, the design document, the decision
index and the known limits), which name what *was* and what *is not built yet*, and say so.
A failure here is fixed in the page, never by widening an allowance.
"""
from __future__ import annotations

import json
import pathlib
import re
import shlex
import sys
from collections.abc import Iterator

import pytest
import yaml

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = CONSOLE_ROOT.parent
sys.path.insert(0, str(CONSOLE_ROOT))

from core.content.codes import every_code  # noqa: E402
from core.content.frontmatter import FENCE  # noqa: E402

CONTENT = CONSOLE_ROOT / "content"

#: Documents a guide includes that record what was or what is not yet: history and plans may name
#: a command that was retired or a metric that is designed, and say so. Everything else is checked.
HISTORICAL = {"docs/project/RELEASE_NOTES.md", "docs/development/REMAINING.md", "docs/design/system_design.md",
              "docs/design/adr/README.md", "docs/guides/LIMITS.md"}


def _front(path: pathlib.Path) -> tuple[dict, str]:
    text = path.read_text(encoding="utf-8")
    match = FENCE.match(text)
    if not match:
        return {}, text
    return yaml.safe_load(match.group(1)) or {}, text[match.end():]


def documents(include_historical: bool = False) -> Iterator[tuple[str, str]]:
    """``(label, text)`` for every page a reader of the help can open: a topic's own text, and for
    a guide or tutorial the document it includes."""
    seen: set[str] = set()
    for area in ("topics", "help", "tutorials"):
        for path in sorted((CONTENT / area).glob("*.md")):
            meta, body = _front(path)
            source = meta.get("include")
            if source:
                if source in seen or (source in HISTORICAL and not include_historical):
                    continue
                seen.add(source)
                target = REPO_ROOT / source
                if target.exists():
                    yield source, target.read_text(encoding="utf-8")
            else:
                yield f"console/content/{area}/{path.name}", body


def _without_html_comments(text: str) -> str:
    return re.sub(r"<!--.*?-->", "", text, flags=re.DOTALL)


# ================================================================== PRV codes

def _declared_codes() -> set[str]:
    codes: set[str] = set()
    for source in _java_sources():
        for number in re.findall(r"new ErrorCode\(\s*(\d+)\s*,", source.read_text(encoding="utf-8")):
            codes.add(f"PRV-{int(number):04d}")
    return codes


def test_every_code_a_page_names_is_one_the_engine_declares_and_the_browser_lists():
    declared = _declared_codes()
    listed = {entry["code"] for entry in every_code(REPO_ROOT / "docs")}
    assert len(declared) > 150 and len(listed) > 150
    # A code the engine writes as a bare string rather than through an ErrorCode: true to name,
    # because it is what a client receives. There are none today -- the HTTP API's PRV-0400 for a
    # malformed parameter is PRV-1051 now (PRV0400-1) -- and the next one should be declared.
    emitted = {c for s in _java_sources() for c in re.findall(r'"(PRV-\d{4})"', s.read_text(encoding="utf-8"))}
    checked = 0
    unknown = []
    for label, text in documents():
        for code in sorted(set(re.findall(r"\bPRV-\d{4}\b", text))):
            checked += 1
            if code in emitted - declared:
                continue
            if code not in declared:
                unknown.append(f"{label}: {code} is declared nowhere in the engine")
            elif code not in listed:
                unknown.append(f"{label}: {code} is not in TROUBLESHOOTING.md's table, so it has no page")
    assert checked > 300, f"only {checked} code mentions checked"
    assert not unknown, "\n  ".join(unknown)


def test_every_code_the_engine_declares_is_in_the_table_the_code_browser_lists():
    # The table is where /help/codes gets its list; a plugin's code missing from it is a code a
    # log line names and the browser does not.
    missing = sorted(_declared_codes() - {entry["code"] for entry in every_code(REPO_ROOT / "docs")})
    assert not missing, f"declared but not in TROUBLESHOOTING.md's 'Every code' table: {missing}"


# ================================================================== settings and their defaults

def _flatten(node, prefix: str = "") -> dict[str, object]:
    out: dict[str, object] = {}
    if isinstance(node, dict):
        for key, value in node.items():
            name = f"{prefix}.{key}" if prefix else str(key)
            if isinstance(value, dict) and value:
                out.update(_flatten(value, name))
            else:
                out[name] = value
    return out


def _server_yaml() -> dict[str, object]:
    path = REPO_ROOT / "pravaha-server" / "src" / "main" / "resources" / "application.yaml"
    return _flatten(yaml.safe_load(path.read_text(encoding="utf-8")))


def _java_sources() -> list[pathlib.Path]:
    return [*REPO_ROOT.glob("*/src/main/java/**/*.java"), *REPO_ROOT.glob("plugins/*/src/main/java/**/*.java"),
            *REPO_ROOT.glob("sdk/*/src/main/java/**/*.java")]


_DURATION = {"ofMillis": 0.001, "ofSeconds": 1, "ofMinutes": 60, "ofHours": 3600, "ofDays": 86400}


def _code_defaults() -> dict[str, str]:
    """``${pravaha.key:default}`` placeholders, and the literal initialisers of the top-level fields
    of every ``@ConfigurationProperties`` class, keyed by setting."""
    found: dict[str, str] = {}
    for source in _java_sources():
        text = source.read_text(encoding="utf-8")
        for key, default in re.findall(r"\$\{(pravaha\.[a-z0-9.-]+):([^}]*)\}", text):
            found.setdefault(key, default)
        prefix = re.search(r'@ConfigurationProperties\(prefix\s*=\s*"(pravaha[a-z0-9.-]*)"', text)
        if not prefix:
            continue
        for field, value in re.findall(
                r"^    private (?:[A-Za-z<>, ?.]+?) ([a-z][A-Za-z0-9]*)\s*=\s*([^;]+);", text, re.MULTILINE):
            name = prefix.group(1) + "." + re.sub(r"(?<!^)([A-Z])", r"-\1", field).lower()
            value = value.strip()
            duration = re.fullmatch(r"Duration\.(of[A-Za-z]+)\((\d+)L?\)", value)
            if duration and duration.group(1) in _DURATION:
                found.setdefault(name, f"{int(duration.group(2)) * _DURATION[duration.group(1)]}s")
            elif re.fullmatch(r'"[^"]*"|-?\d+(\.\d+)?[LlDdFf]?|true|false', value):
                found.setdefault(name, value.strip('"').rstrip("LlDdFf") if not value.startswith('"') else value[1:-1])
    return found


_UNITS = {"b": 1, "kb": 1024, "kib": 1024, "mb": 1024 ** 2, "mib": 1024 ** 2, "gb": 1024 ** 3, "gib": 1024 ** 3}
_TIMES = {"ms": 0.001, "s": 1, "m": 60, "h": 3600, "d": 86400}


def _normal(value: object) -> str:
    """One spelling for a default, so `1m`, `PT1M` and `60s` compare equal and `4 MiB` equals 4194304."""
    text = str(value).strip().strip('"').strip("'").strip()
    lower = text.lower()
    if lower in ("", "none", "empty", "unset", "null", "(empty)", "*empty*", "*none*", "[]", "{}"):
        return ""
    if isinstance(value, bool) or lower in ("true", "false"):
        return lower
    iso = re.fullmatch(r"pt(?:(\d+)h)?(?:(\d+)m)?(?:(\d+(?:\.\d+)?)s)?", lower)
    if iso and any(iso.groups()):
        h, m, s = (float(g or 0) for g in iso.groups())
        return f"{h * 3600 + m * 60 + s:g}s"
    iso_days = re.fullmatch(r"p(\d+)d", lower)
    if iso_days:
        return f"{int(iso_days.group(1)) * 86400:g}s"
    duration = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(ms|s|m|h|d)", lower)
    if duration:
        return f"{float(duration.group(1)) * _TIMES[duration.group(2)]:g}s"
    size = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(b|kb|kib|mb|mib|gb|gib)", lower)
    if size:
        return f"{float(size.group(1)) * _UNITS[size.group(2)]:g}"
    number = re.fullmatch(r"-?\d+(?:\.\d+)?", lower)
    if number:
        return f"{float(lower):g}"
    return lower


def _stated_defaults(pattern: str = r"pravaha\.[a-z0-9.-]+[a-z0-9]") -> Iterator[tuple[str, str, str]]:
    """``(document, setting, stated default)`` from every table with a Default column whose row
    starts with one backticked setting matching ``pattern``."""
    for label, text in documents():
        header: list[str] | None = None
        for line in text.splitlines():
            if not line.startswith("|"):
                header = None
                continue
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if header is None:
                header = [c.lower() for c in cells]
                continue
            if set(line.replace("|", "").strip()) <= set("-: "):
                continue
            if "default" not in header or len(cells) != len(header):
                continue
            key = re.fullmatch(r"`(" + pattern + r")`", cells[0])
            if not key:
                continue
            cell = cells[header.index("default")]
            if re.fullmatch(r"\*[^*]+\*", cell):
                stated = cell.strip("*")
            elif re.fullmatch(r"\d+(?:\.\d+)?|true|false", cell):
                stated = cell
            else:
                # One value, perhaps with a gloss in brackets: `67108864` (64 MiB). A cell that goes on
                # to describe a derived default ("`x.journal` beside ...") states no single value.
                token = re.fullmatch(r"`([^`]*)`(?:\s*\([^)]*\))?", cell)
                if not token:
                    continue
                stated = token.group(1)
            yield label, key.group(1), stated


def test_every_default_a_settings_table_states_is_the_one_the_node_has():
    shipped = {k: v for k, v in _server_yaml().items() if k.startswith("pravaha.")}
    code = _code_defaults()
    checked, wrong = 0, []
    for label, key, stated in _stated_defaults():
        if key in shipped and not isinstance(shipped[key], (dict, list)):
            actual, where = shipped[key], "application.yaml"
            placeholder = re.fullmatch(r"\$\{[A-Z0-9_]+:([^}]*)\}", str(actual))
            if placeholder:
                actual = placeholder.group(1)
        elif key in code:
            actual, where = code[key], "the source"
        else:
            continue
        checked += 1
        if _normal(stated) != _normal(actual):
            wrong.append(f"{label}: {key} says default `{stated}`, {where} has `{actual}`")
    assert checked >= 60, f"only {checked} stated defaults could be checked"
    assert not wrong, f"{len(wrong)} wrong defaults:\n  " + "\n  ".join(wrong)


def test_every_setting_a_guide_or_tutorial_names_exists():
    # The topics are checked by test_help.test_every_setting_a_topic_names_exists; the same rule
    # over the documents the guides and tutorials render in place.
    from tests.test_help import MAP_SETTINGS, USER_NAMED, _declared_settings

    declared = _declared_settings()
    prefixes = {k.rsplit(".", 1)[0] for k in declared}
    sdk = REPO_ROOT / "sdk" / "python"
    unknown = []
    for label, text in documents():
        if label.startswith("console/content/topics/"):
            continue
        for name in set(re.findall(r"`(pravaha\.[a-z][a-z0-9.-]*[a-z0-9])(?:[=:][^`]*)?`", text)):
            if name.startswith(USER_NAMED) or name.endswith(".*") or \
                    any(name.startswith(m + ".") for m in MAP_SETTINGS):
                continue
            module = sdk.joinpath(*name.split("."))
            if module.is_dir() or module.with_suffix(".py").exists():
                continue            # `pravaha.assist` is the Python SDK's package, not a setting
            if name not in declared and name not in prefixes:
                unknown.append(f"{label}: {name}")
    assert not unknown, "settings nothing declares:\n  " + "\n  ".join(sorted(unknown))


#: The console's own settings sections (config/application.yaml), as the help writes their keys.
CONSOLE_SECTIONS = r"(?:console|ui|engine|app|assist|metrics)"


def _console_config() -> dict[str, object]:
    return _flatten(yaml.safe_load((CONSOLE_ROOT / "config" / "application.yaml").read_text(encoding="utf-8")))


def _console_reads() -> set[str]:
    """Every key the console's config file has, and every one its code reads by name (a key read
    with a default and left out of the file, such as ``app.environment``)."""
    config = _console_config()
    known = set(config) | {k.rsplit(".", 1)[0] for k in config}
    for source in [*(CONSOLE_ROOT / "core").rglob("*.py"), *(CONSOLE_ROOT / "routes").rglob("*.py"),
                   CONSOLE_ROOT / "run_pravaha_web.py"]:
        known |= set(re.findall(r'\.get(?:_int|_bool|_list|_float)?\(\s*"(' + CONSOLE_SECTIONS + r'\.[a-z_.]+)"',
                                source.read_text(encoding="utf-8")))
    return known


def test_every_console_setting_the_help_names_is_one_the_console_reads():
    known = _console_reads()
    unknown = []
    for label, text in documents():
        for name in set(re.findall(r"`(" + CONSOLE_SECTIONS + r"\.[a-z_][a-z0-9_.]*[a-z0-9_])`", text)):
            if name.endswith((".json", ".yaml", ".yml", ".py", ".md")):
                continue                    # a file called assist.yaml, not a key
            if name not in known:
                unknown.append(f"{label}: {name}")
    assert not unknown, "console settings the console neither has nor reads:\n  " + "\n  ".join(unknown)


def test_every_console_default_the_help_states_is_the_one_the_console_has():
    config = _console_config()
    checked, wrong = 0, []
    for label, key, stated in _stated_defaults(CONSOLE_SECTIONS + r"\.[a-z_.]+[a-z_]"):
        if key not in config:
            continue
        actual = str(config[key])
        placeholder = re.fullmatch(r"\$\{[A-Z0-9_]+:([^}]*)\}", actual)
        if placeholder:
            actual = placeholder.group(1)
        checked += 1
        if _normal(stated) != _normal(actual):
            wrong.append(f"{label}: {key} says `{stated}`, config/application.yaml has `{actual}`")
    assert checked >= 5, f"only {checked} console defaults checked"
    assert not wrong, "\n  ".join(wrong)


# ================================================================== the command lines

def _python_cli():
    sys.path.insert(0, str(REPO_ROOT / "sdk" / "python"))
    from pravaha.cli._app import build_parser

    return build_parser()


def _choices(parser) -> dict:
    for action in parser._actions:
        if getattr(action, "choices", None) and hasattr(action, "_name_parser_map"):
            return action.choices
    return {}


def _java_cli() -> dict[str, set[str]]:
    """``pravaha-engine``: each command and the flags its class reads."""
    root = REPO_ROOT / "pravaha-cli" / "src" / "main" / "java" / "com" / "ash" / "messaging" / "pravaha" / "cli"
    shared = set(re.findall(r'args\.(?:require|get|has|flag)\("([a-z-]+)"', (root / "QueryRunner.java").read_text()))
    commands: dict[str, set[str]] = {"version": set()}
    for name, cls in (("validate", "ValidateCommand"), ("explain", "ExplainCommand"), ("run", "RunCommand")):
        text = (root / f"{cls}.java").read_text(encoding="utf-8")
        commands[name] = set(re.findall(r'args\.(?:require|get|has|flag)\("([a-z-]+)"', text)) | \
            (shared if name == "run" else set())
    usage = (root / "PravahaCli.java").read_text(encoding="utf-8")
    for name in commands:
        assert f'"{name}"' in usage, f"pravaha-engine does not dispatch {name}"
    return commands


_CODE_BLOCK = re.compile(r"^(```|~~~)[^\n]*\n(.*?)^\1", re.MULTILINE | re.DOTALL)


def _command_lines(text: str) -> Iterator[str]:
    """Every ``pravaha ...`` / ``pravaha-engine ...`` command a page shows: a line of a code block
    (continuations joined), or an inline code span."""
    text = _without_html_comments(text)
    for block in _CODE_BLOCK.finditer(text):
        body = block.group(2).replace("\\\n", " ")
        for line in body.splitlines():
            line = re.sub(r"^\s*(?:\$|>|#)?\s*", "", line)
            line = re.sub(r"^(?:[A-Z_]+=\S+\s+)+", "", line)      # PRAVAHA_TOKEN=... pravaha ...
            yield from _commands_in(line)
    prose = _CODE_BLOCK.sub("", text)
    for span in re.findall(r"`(pravaha(?:-engine)?\s[^`]+)`", prose):
        yield from _commands_in(span)


def _commands_in(line: str) -> Iterator[str]:
    """The commands on one line: `a; b` and `a && b` are two."""
    for part in re.split(r"\s*(?:;|&&)\s+", line):
        if re.match(r"pravaha(-engine)?\s", part):
            yield part


def _words(line: str) -> list[str]:
    line = line.split(" #", 1)[0]
    for stop in (" | ", " > ", " && ", " ; ", " 2>", " < "):
        line = line.split(stop, 1)[0]
    try:
        return shlex.split(line)
    except ValueError:
        return line.split()


def _flags(words: list[str]) -> list[str]:
    out = []
    for word in words:
        for flag in re.findall(r"(?:^|[\[(|])(--?[a-zA-Z][a-zA-Z0-9-]*)", word):
            out.append(flag)
    return out


def test_every_command_and_flag_a_page_shows_is_one_the_cli_has():
    parser = _python_cli()
    commands = _choices(parser)
    engine = _java_cli()
    top = set(parser._option_string_actions)
    problems, checked = [], 0
    for label, text in documents():
        for line in _command_lines(text):
            words = _words(line)
            if not words:
                continue
            checked += 1
            if words[0] == "pravaha-engine":
                rest = words[1:]
                if not rest or rest[0].startswith(("-", "<")) or rest[0] in ("help",):
                    continue
                if rest[0] not in engine:
                    problems.append(f"{label}: `pravaha-engine {rest[0]}` -- the Java tool has "
                                    f"{sorted(engine)} (`{line.strip()}`)")
                    continue
                for flag in _flags(rest[1:]):
                    if flag not in ("--help", "-h") and flag.lstrip("-") not in engine[rest[0]]:
                        problems.append(f"{label}: `pravaha-engine {rest[0]}` has no {flag} (`{line.strip()}`)")
                continue
            rest = words[1:]
            # Options before the command are the connection options every command takes.
            while rest and rest[0].startswith("-"):
                flag = rest.pop(0).split("=", 1)[0]
                if flag not in top:
                    problems.append(f"{label}: `pravaha {flag}` is not an option (`{line.strip()}`)")
                elif parser._option_string_actions[flag].nargs != 0 and rest and not rest[0].startswith("-"):
                    rest.pop(0)
            if not rest or rest[0].startswith(("<", "[", "(", "...")) or rest[0] in ("…",):
                continue
            names = rest[0].split("|")
            for name in names:
                if name not in commands:
                    problems.append(f"{label}: `pravaha {name}` is not a command (`{line.strip()}`)")
            command = commands.get(names[0])
            if command is None:
                continue
            target = command
            verbs = _choices(command)
            if verbs and len(rest) > 1 and not rest[1].startswith(("-", "<", "(", "[-", "[<")):
                verb_names = rest[1].strip("[]").split("|")
                positional = [a for a in command._actions
                              if not a.option_strings and not hasattr(a, "_name_parser_map")]
                if all(v in verbs for v in verb_names):
                    target = verbs[verb_names[0]]
                elif not positional:
                    problems.append(f"{label}: `pravaha {names[0]} {rest[1]}` -- {names[0]} has "
                                    f"{sorted(verbs)} (`{line.strip()}`)")
            known = set(target._option_string_actions)
            for flag in _flags(rest[1:]):
                if flag not in known:
                    problems.append(f"{label}: `pravaha {' '.join(rest[:2])}` has no {flag} (`{line.strip()}`)")
    assert checked > 150, f"only {checked} command lines checked"
    assert not problems, f"{len(problems)} commands or flags the CLIs do not have:\n  " + "\n  ".join(problems)


# ================================================================== the REST API

def _openapi() -> dict[str, set[str]]:
    lock = json.loads((REPO_ROOT / "api" / "openapi.lock.json").read_text(encoding="utf-8"))
    return {path: {m.upper() for m in methods} for path, methods in lock["paths"].items()}


def _route(path: str, api: dict[str, set[str]]) -> str | None:
    path = path.split("?", 1)[0].split("#", 1)[0].rstrip("/.,;:)")
    for template in api:
        pattern = "^" + re.sub(r"\\\{[^}]+\\\}", r"[^/]+", re.escape(template)) + "$"
        if re.match(pattern, path):
            return template
    return None


#: Served by Spring itself rather than by a controller the lock records.
SPRING_PATHS = ("/actuator/", "/api/v1/openapi.json", "/api/docs")


def test_every_rest_call_a_page_shows_is_in_the_api():
    api = _openapi()
    problems, checked = [], 0
    for label, text in documents():
        text = _without_html_comments(text)
        calls: list[tuple[str, str, bool]] = []
        for line in text.splitlines():
            # A line that says a call answers 405 claims the method is *not* there: checked as that.
            refused = bool(re.search(r"\b405\b", line))
            calls += [(m, p, refused) for m, p in
                      re.findall(r"\b(GET|POST|PUT|DELETE|PATCH)\s+(/api/v1/[A-Za-z0-9_{}/.?=&<>-]*)", line)]
        for curl in re.findall(r"curl\b[^\n`]*(?:\\\n[^\n`]*)*", text):
            method = re.search(r"-X\s*(GET|POST|PUT|DELETE|PATCH)", curl)
            for path in re.findall(r"https?://[^/\s\"']+(/api/v1/[A-Za-z0-9_{}/.?=&<>$-]*)", curl):
                verb = method.group(1) if method else ("POST" if re.search(r"\s(-d|--data\S*)\s", curl) else "GET")
                calls.append((verb, path, False))
        for method, path, refused in calls:
            if path.startswith(SPRING_PATHS):
                continue
            checked += 1
            template = _route(re.sub(r"<[^>]+>", "x", path), api)
            if template is None:
                problems.append(f"{label}: {method} {path} is no path in api/openapi.lock.json")
            elif refused and method in api[template]:
                problems.append(f"{label}: says {method} {path} answers 405, and {template} has it")
            elif not refused and method not in api[template]:
                problems.append(f"{label}: {method} {path} -- {template} answers {sorted(api[template])}")
    assert checked > 60, f"only {checked} REST calls checked"
    assert not problems, f"{len(problems)} REST calls the API does not have:\n  " + "\n  ".join(sorted(set(problems)))


# ================================================================== metrics

_SUFFIXES = ("_total", "_count", "_sum", "_max", "_bucket", "_created")


def _published_metrics() -> set[str]:
    """Every metric name the engine and the console can publish, from the names their sources give
    their meters (Micrometer's dots become underscores on the scrape) and the console's own
    exposition."""
    names: set[str] = set()
    for source in _java_sources():
        for literal in re.findall(r'"(pravaha[._][a-z0-9._]*[a-z0-9])"', source.read_text(encoding="utf-8")):
            names.add(literal.replace(".", "_"))
    for source in (CONSOLE_ROOT / "core").rglob("*.py"):
        names |= set(re.findall(r"\b(pravaha_console_[a-z0-9_]*[a-z0-9])\b", source.read_text(encoding="utf-8")))
    return names


def _is_published(name: str, published: set[str]) -> bool:
    candidates = {name}
    for suffix in _SUFFIXES:
        if name.endswith(suffix):
            candidates.add(name[: -len(suffix)])
    # A timer publishes <name>_seconds_*, a byte summary <name>_bytes_*.
    # An observation's long-task timer adds _active_seconds_*.
    for base in list(candidates):
        for unit in ("_seconds", "_bytes", "_active_seconds"):
            if base.endswith(unit):
                candidates.add(base[: -len(unit)])
    return bool(candidates & published)


def test_every_metric_a_page_names_is_one_that_is_published():
    published = _published_metrics()
    assert len(published) > 100
    problems, checked = [], 0
    for label, text in documents():
        text = _without_html_comments(text)
        for name, family in sorted(set(re.findall(r"\b(pravaha_[a-z0-9]+_[a-z0-9_]*[a-z0-9])(_?\*|[\"'])?", text))):
            # A one-word pravaha_<word> is a database, a schema or a topic in an example, not a metric.
            # `name_*`, or a quoted `"name"` a snippet matches with startswith, is a family of metrics.
            checked += 1
            if family:
                if not any(p.startswith(name + "_") or p == name for p in published):
                    problems.append(f"{label}: {name}* (no metric starts so)")
            elif not _is_published(name, published):
                problems.append(f"{label}: {name}")
    assert checked > 80, f"only {checked} metric names checked"
    assert not problems, f"{len(problems)} metrics nothing publishes:\n  " + "\n  ".join(problems)


@pytest.mark.parametrize("name,ok", [("pravaha_query_checkpoint_failures_total", True),
                                     ("pravaha_query_rows_in_total_nonsense", False)])
def test_the_metric_check_itself_tells_a_real_name_from_an_invented_one(name, ok):
    assert _is_published(name, _published_metrics()) is ok


# ================================================================== the version a page tells you to use

def test_every_dependency_version_a_page_shows_is_the_build_s_own():
    # A <dependency> block, an image tag or an artifact version copied from the help has to resolve
    # against this build; four pages still said 0.1.0-SNAPSHOT two releases on.
    version = re.search(r"<artifactId>pravaha</artifactId>\s*<version>([^<]+)</version>",
                        (REPO_ROOT / "pom.xml").read_text(encoding="utf-8")).group(1)
    wrong, checked = [], 0
    for label, text in documents():
        for found in re.findall(r"<version>(\d+\.\d+\.\d+-SNAPSHOT)</version>|pravaha-server:(\d+\.\d+\.\d+-SNAPSHOT)"
                                r"|version `(\d+\.\d+\.\d+-SNAPSHOT)` in this repository"
                                r"|\"version\": \"(\d+\.\d+\.\d+-SNAPSHOT)\"", text):
            stated = next(v for v in found if v)
            checked += 1
            if stated != version:
                wrong.append(f"{label}: {stated}")
    assert checked >= 5, f"only {checked} versions checked"
    assert not wrong, f"the build is {version}:\n  " + "\n  ".join(wrong)
