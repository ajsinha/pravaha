#!/usr/bin/env python3
"""Project Pravaha -- Ask once. Answer always.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see the LICENSE file in the root of this repository.

Do the GitHub Actions workflows parse, and do they say what a workflow has to say?

    deploy/ci/check-workflows.py                 # .github/workflows/*.yml
    deploy/ci/check-workflows.py path/to/dir     # somewhere else (the self-test uses this)

WHY THIS EXISTS. GitHub Actions is validated by GitHub, on push, after the commit. On a machine
whose CI has never run -- which is this one -- a workflow is an unparsed text file that looks
fine. Every rule below is something that would otherwise be found by a push that does nothing,
or worse, by a job that is green because it ran nothing.

THE `on:` TRAP. YAML 1.1 reads a bare `on` as the boolean true, so `workflow["on"]` is a
KeyError and `workflow[True]` is the trigger block. A checker that looked up "on" would report
every workflow in the world as having no trigger. Handled below, and named, because the next
person to write a checker here will meet it too.

Exit 0 when everything passes, 1 with one line per problem otherwise.
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - the message is the point
    sys.exit(
        "check-workflows.py: PyYAML is not installed.\n"
        "  pip install pyyaml   (or run this through a venv that has it)\n"
        "  This is not a skip: a workflow nothing parsed is a workflow nobody has checked."
    )

# Actions whose floating references are refused. A workflow pinned to @main runs whatever that
# branch holds today, which makes a green build yesterday no evidence about today.
FLOATING = {"main", "master", "latest", "HEAD"}

# The one JDK a workflow may set up (ADR-061: Pravaha 2.x builds, tests and runs on Java 25 only).
# A leg on another JDK is either testing a version nobody supports or failing at the enforcer.
JAVA = "25"


def problems_in(path: Path) -> list[str]:
    found: list[str] = []

    def bad(message: str) -> None:
        found.append(f"{path.name}: {message}")

    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        return [f"{path.name}: cannot be read: {error}"]

    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError as error:
        return [f"{path.name}: does not parse as YAML: {error}"]

    if not isinstance(doc, dict):
        return [f"{path.name}: is not a mapping at the top level"]

    # `on` is the YAML 1.1 boolean true once parsed. See the module docstring.
    triggers = doc.get(True, doc.get("on"))
    if not triggers:
        bad("has no `on:` trigger, so it never runs")

    if not doc.get("name"):
        bad("has no `name:`, so it appears in the checks list as its filename")

    jobs = doc.get("jobs")
    if not isinstance(jobs, dict) or not jobs:
        return found + [f"{path.name}: has no jobs"]

    for job_id, job in jobs.items():
        where = f"job '{job_id}'"
        if not isinstance(job, dict):
            bad(f"{where} is not a mapping")
            continue

        if "uses" in job:
            # A reusable-workflow call has no runs-on or steps of its own.
            continue

        if not job.get("runs-on"):
            bad(f"{where} has no runs-on")

        # A job with no timeout inherits GitHub's six hours. A hung job that burns six hours of
        # a runner before anybody looks at it is the failure mode this catches.
        if "timeout-minutes" not in job:
            bad(f"{where} has no timeout-minutes; it would hang for GitHub's default six hours")

        steps = job.get("steps")
        if not isinstance(steps, list) or not steps:
            bad(f"{where} has no steps")
            continue

        for index, step in enumerate(steps):
            at = f"{where} step {index + 1}"
            if not isinstance(step, dict):
                bad(f"{at} is not a mapping")
                continue
            if "uses" not in step and "run" not in step:
                bad(f"{at} has neither `uses` nor `run`, so it does nothing")
            if "uses" in step and "run" in step:
                bad(f"{at} has both `uses` and `run`; only one of them happens")
            uses = step.get("uses")
            if isinstance(uses, str) and not uses.startswith("./"):
                if "@" not in uses:
                    bad(f"{at} uses '{uses}' with no version; it would follow the default branch")
                elif uses.rsplit("@", 1)[1] in FLOATING:
                    bad(f"{at} uses '{uses}', a floating ref; pin it to a tag or a sha")
                if uses.startswith("actions/setup-java@"):
                    wanted = str((step.get("with") or {}).get("java-version", "")).split()
                    if wanted != [JAVA]:
                        bad(f"{at} sets up Java {' '.join(wanted) or '(none named)'}; "
                            f"Pravaha 2.x builds and runs on Java {JAVA} only (ADR-061)")

    return found


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parents[2] / ".github/workflows"
    if not root.is_dir():
        print(f"check-workflows.py: no such directory: {root}", file=sys.stderr)
        return 1

    files = sorted(p for p in root.iterdir() if p.suffix in (".yml", ".yaml"))
    if not files:
        # A checker that passes over an empty directory is a checker that will one day pass over
        # a directory somebody emptied.
        print(f"check-workflows.py: no workflow files in {root}", file=sys.stderr)
        return 1

    problems: list[str] = []
    for path in files:
        found = problems_in(path)
        problems.extend(found)
        jobs = "?" if found and "has no jobs" in " ".join(found) else ""
        print(f"{'FAIL' if found else 'ok  '}  {path.name}{jobs}")

    if problems:
        print(file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        print(f"\ncheck-workflows.py: {len(problems)} problem(s) in {len(files)} workflow(s)", file=sys.stderr)
        return 1

    print(f"\ncheck-workflows.py: {len(files)} workflow(s) parse and are wired")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
