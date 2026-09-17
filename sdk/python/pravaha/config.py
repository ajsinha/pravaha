"""Layers environment-variable overrides onto a plain config map.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Every ``from_config`` in this package (``TlsOptions.from_config``,
``Endpoint.from_config``, ``ClientOptions.from_config``) reads a plain
``Mapping[str, str]`` of dotted keys such as ``tls.ca-certificate`` --
deliberately no file format or reference resolution of its own, because that
belongs to whatever the embedding application already uses to load its own
configuration (an ``.ini`` file, a YAML loader, a secrets manager) and is not
this SDK's business to reinvent. What is this SDK's business is the one thing
every configuration source in this project agrees on: an environment variable
can override a key without editing the file that set it. :func:`layered` is
that, and only that -- the Java SDK's ``SdkConfig.layered`` is its exact
counterpart, so a team running both reads one rule, not two.
"""

from __future__ import annotations

import os
from typing import Dict, Mapping


def layered(base: Mapping[str, str], prefix: str = "PRAVAHA_") -> Dict[str, str]:
    """Merges ``base`` with environment variables whose name starts with ``prefix``.

    The prefix is stripped and the remainder lower-cased with underscores turned
    into dots, so ``PRAVAHA_TLS_ENABLED=false`` overrides a base-map
    ``tls.enabled`` -- matching the translation this project's own server-side
    ``ConfigurationBuilder.addEnvironment`` uses, so an operator who knows that
    convention from the server does not have to learn a second one for this
    client. The same underscore-only limitation applies here as there: a
    hyphenated key segment (``trust-store``) cannot be expressed as an
    environment variable, because ``_`` is the only word separator a shell
    environment variable name can contain and every one of them is read as a
    dot; this is a documented gap in the server's own convention, not a new one.
    """
    merged = dict(base)
    upper_prefix = prefix.upper()
    for name, value in os.environ.items():
        upper = name.upper()
        if upper.startswith(upper_prefix):
            key = upper[len(upper_prefix) :].lower().replace("_", ".")
            merged[key] = value
    return merged
