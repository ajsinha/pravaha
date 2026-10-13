"""ADR-064: the console puts ``<external_lib>/python`` on ``sys.path``, and a module there imports.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""
from __future__ import annotations

import importlib
import sys

from core import external_lib


def test_a_module_in_external_lib_python_imports(tmp_path, monkeypatch):
    (tmp_path / "python").mkdir()
    (tmp_path / "python" / "site_extension_probe.py").write_text("ANSWER = 42\n")
    monkeypatch.setattr(sys, "path", list(sys.path))
    added = external_lib.add_to_sys_path(str(tmp_path), environ={})
    assert added == (tmp_path / "python").resolve()
    assert sys.path[0] == str(added)
    assert importlib.import_module("site_extension_probe").ANSWER == 42


def test_the_root_is_the_setting_then_the_environment_then_the_home(tmp_path):
    assert external_lib.root("/srv/x", {"PRAVAHA_EXTERNAL_LIB": "/srv/y"}).as_posix() == "/srv/x"
    assert external_lib.root(None, {"PRAVAHA_EXTERNAL_LIB": "/srv/y"}).as_posix() == "/srv/y"
    assert external_lib.root("", {"PRAVAHA_HOME": "/opt/pravaha"}).as_posix() == "/opt/pravaha/external_lib"
    assert external_lib.root(None, {}) == external_lib.CHECKOUT_DEFAULT


def test_a_missing_folder_adds_nothing(tmp_path):
    assert external_lib.add_to_sys_path(str(tmp_path / "nowhere"), environ={}) is None
