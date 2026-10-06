"""
Pravaha Python SDK -- the package says which version it is.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Found while writing the API guide: ``pravaha.__version__`` was the literal "0.1.0", which the release
script never changed, so the 0.1.1 wheel reported 0.1.0.
"""
import re
from pathlib import Path


def test_a_source_checkout_reports_its_own_pyproject_version():
    """Imported from this tree (an editable install), the version is the tree's, not the one the
    install recorded: after set-version.sh moved the tree on, stale metadata made `pravaha doctor`
    report a version mismatch with the node built from the same tree."""
    import pravaha

    pyproject = (Path(__file__).resolve().parent.parent / "pyproject.toml").read_text(encoding="utf-8")
    expected = re.search(r'^version\s*=\s*"([^"]+)"', pyproject, re.M)
    assert expected is not None
    assert pravaha.__version__ == expected.group(1)


def test_an_installed_wheel_reports_the_version_it_was_installed_as_not_a_literal(monkeypatch):
    """A wheel has no pyproject.toml beside the package, so its own metadata decides."""
    from importlib.metadata import PackageNotFoundError, version

    import pravaha

    monkeypatch.setattr(pravaha, "_source_version", lambda: None)
    try:
        expected = version("pravaha")
    except PackageNotFoundError:
        expected = "unknown"
    assert pravaha._installed_version() == expected
