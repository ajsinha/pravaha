"""A small JSON Schema validator: the subset the assistant's answers use, and no more.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Standard library only, so the SDK's base install gains no dependency for it. It understands
``type`` (one or a list), ``properties``, ``required``, ``additionalProperties`` (a boolean or a
schema), ``items``, ``enum``, ``const``, ``minLength``/``maxLength``, ``minimum``/``maximum`` and
``minItems``/``maxItems``; ``title``, ``description``, ``default``, ``examples``, ``$schema``,
``$id`` and ``$comment`` are annotations and ignored.

A schema that uses anything else is refused by :func:`check_schema` rather than half-checked: a
keyword silently skipped is a constraint the caller believes holds and does not.
"""

from __future__ import annotations

import math
from typing import Any, Mapping

#: Keywords this validator enforces.
KEYWORDS = frozenset(
    {
        "type",
        "properties",
        "required",
        "additionalProperties",
        "items",
        "enum",
        "const",
        "minLength",
        "maxLength",
        "minimum",
        "maximum",
        "minItems",
        "maxItems",
    }
)
#: Keywords that describe and do not constrain.
ANNOTATIONS = frozenset({"title", "description", "default", "examples", "$schema", "$id", "$comment"})
TYPES = frozenset({"object", "array", "string", "integer", "number", "boolean", "null"})


class SchemaError(ValueError):
    """The schema itself uses something this validator does not enforce, or is malformed."""


class SchemaValidationError(ValueError):
    """An instance that does not satisfy its schema. ``problems`` lists every one found."""

    def __init__(self, problems: "list[str]") -> None:
        super().__init__("; ".join(problems))
        self.problems = problems


def check_schema(schema: Any, path: str = "#") -> None:
    """Refuses a schema that uses a keyword this module does not enforce."""
    if not isinstance(schema, Mapping):
        raise SchemaError(f"{path}: a schema is a JSON object, not {type(schema).__name__}")
    unknown = sorted(set(schema) - KEYWORDS - ANNOTATIONS)
    if unknown:
        raise SchemaError(
            f"{path}: {', '.join(unknown)} {'is' if len(unknown) == 1 else 'are'} not enforced by "
            f"pravaha.assist.schema; supported: {', '.join(sorted(KEYWORDS))}"
        )
    kinds = schema.get("type")
    for kind in kinds if isinstance(kinds, list) else [kinds] if kinds is not None else []:
        if kind not in TYPES:
            raise SchemaError(f"{path}/type: unknown type {kind!r}")
    for name, sub in (schema.get("properties") or {}).items():
        check_schema(sub, f"{path}/properties/{name}")
    if isinstance(schema.get("additionalProperties"), Mapping):
        check_schema(schema["additionalProperties"], f"{path}/additionalProperties")
    if "items" in schema:
        check_schema(schema["items"], f"{path}/items")


def _type_matches(value: Any, kind: str) -> bool:
    if kind == "null":
        return value is None
    if kind == "boolean":
        return isinstance(value, bool)
    if kind == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if kind == "number":
        return (
            isinstance(value, (int, float))
            and not isinstance(value, bool)
            and not (isinstance(value, float) and not math.isfinite(value))
        )
    if kind == "string":
        return isinstance(value, str)
    if kind == "array":
        return isinstance(value, list)
    if kind == "object":
        return isinstance(value, dict)
    return False


def _describe(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return type(value).__name__


def problems(instance: Any, schema: Mapping[str, Any], path: str = "$") -> "list[str]":
    """Every way ``instance`` fails ``schema``, each naming where (``$.steps[2]``)."""
    found: list[str] = []
    kinds = schema.get("type")
    if kinds is not None:
        allowed = kinds if isinstance(kinds, list) else [kinds]
        if not any(_type_matches(instance, k) for k in allowed):
            found.append(f"{path}: expected {' or '.join(allowed)}, got {_describe(instance)}")
            return found
    if "enum" in schema and instance not in schema["enum"]:
        found.append(f"{path}: {instance!r} is not one of {schema['enum']!r}")
    if "const" in schema and instance != schema["const"]:
        found.append(f"{path}: must be {schema['const']!r}")
    if isinstance(instance, str):
        if "minLength" in schema and len(instance) < schema["minLength"]:
            found.append(f"{path}: shorter than {schema['minLength']} characters")
        if "maxLength" in schema and len(instance) > schema["maxLength"]:
            found.append(f"{path}: longer than {schema['maxLength']} characters")
    if _type_matches(instance, "number"):
        if "minimum" in schema and instance < schema["minimum"]:
            found.append(f"{path}: below the minimum {schema['minimum']}")
        if "maximum" in schema and instance > schema["maximum"]:
            found.append(f"{path}: above the maximum {schema['maximum']}")
    if isinstance(instance, list):
        if "minItems" in schema and len(instance) < schema["minItems"]:
            found.append(f"{path}: fewer than {schema['minItems']} items")
        if "maxItems" in schema and len(instance) > schema["maxItems"]:
            found.append(f"{path}: more than {schema['maxItems']} items")
        if isinstance(schema.get("items"), Mapping):
            for i, item in enumerate(instance):
                found.extend(problems(item, schema["items"], f"{path}[{i}]"))
    if isinstance(instance, dict):
        properties: Mapping[str, Any] = schema.get("properties") or {}
        for name in schema.get("required") or []:
            if name not in instance:
                found.append(f"{path}: missing required property {name!r}")
        extra = schema.get("additionalProperties", True)
        for name, value in instance.items():
            if name in properties:
                found.extend(problems(value, properties[name], f"{path}.{name}"))
            elif extra is False:
                found.append(f"{path}: unexpected property {name!r}")
            elif isinstance(extra, Mapping):
                found.extend(problems(value, extra, f"{path}.{name}"))
    return found


def validate(instance: Any, schema: Mapping[str, Any]) -> None:
    """Raises :class:`SchemaValidationError` unless ``instance`` satisfies ``schema``."""
    found = problems(instance, schema)
    if found:
        raise SchemaValidationError(found)


__all__ = [
    "ANNOTATIONS",
    "KEYWORDS",
    "SchemaError",
    "SchemaValidationError",
    "check_schema",
    "problems",
    "validate",
]
