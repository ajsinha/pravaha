"""The base class's structured-output path, and the JSON Schema validator under it.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

JSON asked for in text when the provider cannot constrain it; the answer validated either way;
exactly one repair turn with the validation error; a second failure is ModelOutputError with the
tokens of both turns -- never a guess.
"""

from __future__ import annotations

import json

import pytest

from pravaha.assist import ChatRequest, Message, ModelOutputError
from pravaha.assist.provider import extract_json
from pravaha.assist.providers import FakeProvider
from pravaha.assist.schema import SchemaError, SchemaValidationError, check_schema, problems, validate

SCHEMA = {
    "type": "object",
    "properties": {
        "meaning": {"type": "string"},
        "rewrite": {"type": ["string", "null"]},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
    },
    "required": ["meaning", "rewrite"],
    "additionalProperties": False,
}
GOOD = {"meaning": "unbounded state", "rewrite": None, "confidence": 0.8}


def _ask() -> ChatRequest:
    return ChatRequest(messages=[Message("user", "Explain PRV-2050.")], model="m",
                       system="Be brief.", response_schema=SCHEMA)


def test_a_provider_without_native_schemas_is_asked_for_json_in_text():
    fake = FakeProvider(replies=[json.dumps(GOOD)])
    answer = fake.complete(_ask())
    assert answer.parsed == GOOD
    system = fake.requests[0].system or ""
    assert system.startswith("Be brief.")
    assert "Answer with exactly one JSON object" in system and '"meaning"' in system


def test_a_native_provider_is_not_given_the_text_instruction_but_is_still_validated():
    fake = FakeProvider(replies=['{"meaning": 3, "rewrite": null}', json.dumps(GOOD)],
                        structured_output="json_schema")
    answer = fake.complete(_ask())
    assert answer.parsed == GOOD
    assert fake.requests[0].system == "Be brief."
    assert len(fake.requests) == 2, "a native answer that fails the schema still gets the repair turn"


def test_fenced_or_wrapped_json_is_read():
    assert extract_json("```json\n{\"a\": 1}\n```") == {"a": 1}
    assert extract_json("Here it is: {\"a\": [1, 2]} -- done.") == {"a": [1, 2]}
    with pytest.raises(ValueError, match="no JSON object"):
        extract_json("no json here")


def test_one_repair_turn_carries_the_problem_and_the_bad_answer():
    fake = FakeProvider(replies=["I think it means unbounded state.", json.dumps(GOOD)])
    answer = fake.complete(_ask())
    assert answer.parsed == GOOD
    repair = fake.requests[1]
    assert [m.role for m in repair.messages] == ["user", "assistant", "user"]
    assert repair.messages[1].content == "I think it means unbounded state."
    assert "contains no JSON object" in repair.messages[2].content
    first_usage = fake.requests  # both turns are counted
    assert answer.usage.total_tokens > 0 and len(first_usage) == 2


def test_a_second_failure_is_a_model_output_error_never_a_guess():
    fake = FakeProvider(replies=['{"meaning": "x"}', '{"meaning": "x", "rewrite": 7}'])
    with pytest.raises(ModelOutputError) as caught:
        fake.complete(_ask())
    error = caught.value
    assert len(fake.requests) == 2, "exactly one repair turn"
    assert "$.rewrite: expected string or null, got integer" in error.problem
    assert error.text == '{"meaning": "x", "rewrite": 7}'
    assert error.usage is not None and error.usage.total_tokens > 0
    assert error.provider == "fake"


def test_the_validator_names_every_problem_with_its_path():
    found = problems({"meaning": 1, "extra": True, "confidence": 2}, SCHEMA)
    assert "$.meaning: expected string, got integer" in found
    assert "$: unexpected property 'extra'" in found
    assert "$: missing required property 'rewrite'" in found
    assert "$.confidence: above the maximum 1" in found
    validate(GOOD, SCHEMA)
    with pytest.raises(SchemaValidationError):
        validate([], SCHEMA)


def test_the_validator_distinguishes_booleans_integers_and_numbers():
    assert problems(True, {"type": "integer"})
    assert problems(1.5, {"type": "integer"})
    assert not problems(2, {"type": "number"})
    assert problems(float("nan"), {"type": "number"})
    assert not problems(["a"], {"type": "array", "items": {"type": "string"}, "minItems": 1})
    assert problems([], {"type": "array", "minItems": 1})
    assert problems("x", {"enum": ["a", "b"]})
    assert problems("abc", {"type": "string", "maxLength": 2})


def test_a_schema_using_an_unenforced_keyword_is_refused_not_half_checked():
    with pytest.raises(SchemaError, match="pattern"):
        check_schema({"type": "string", "pattern": "^PRV"})
    with pytest.raises(SchemaError, match="oneOf"):
        check_schema({"type": "object", "properties": {"a": {"oneOf": []}}})
    check_schema({**SCHEMA, "description": "annotations are fine", "$comment": "so is this"})
    fake = FakeProvider(replies=["{}"])
    with pytest.raises(SchemaError):
        fake.complete(ChatRequest(messages=[Message("user", "x")],
                                  response_schema={"type": "string", "format": "date"}))
