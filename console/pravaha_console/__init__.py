"""Operator console for Project Pravaha.

A separate process built on the published SDK (ADR-024), so the API boundary between the
console and the engine cannot be violated: a boundary enforced by a test can be waived,
and a boundary enforced by a process cannot.
"""

__all__ = ["Engine", "create_app"]

from pravaha_console.app import create_app
from pravaha_console.engine import Engine
