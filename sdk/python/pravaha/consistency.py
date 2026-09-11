"""Read consistency modes.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from enum import Enum


class Consistency(str, Enum):
    """What a read of a served view is allowed to see.

    Declared per read rather than configured once, because the right answer differs
    by call within one application: a dashboard tile wants the freshest number
    available, a reconciliation job wants a coherent one. Every response carries its
    own staleness, so the caller can decide whether the answer is fresh enough
    rather than guessing.
    """

    LATEST = "latest"
    """Whatever the owning lane holds right now, including uncommitted work."""

    CONSISTENT = "consistent"
    """As of the latest globally committed frontier.

    The default, and the only mode under which reading two views returns numbers
    that reconcile: both reflect the same prefix of their shared input.
    """

    AS_OF = "as_of"
    """As of a supplied logical time, within checkpoint retention."""

    AT_LEAST = "at_least"
    """Blocks until the frontier reaches a supplied time, then reads."""
