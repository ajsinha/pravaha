"""
Pravaha Python SDK -- a refusal over Flight carries the engine's own code.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Found while writing the API guide against a running node: an ApiError from the REST API
carried the engine's code as ``engine_code``, and a QueryError from Flight carried only this
client's 1041, with the engine's PRV-8002 somewhere inside a message that also held pyarrow's
wrapper and gRPC's debug context. A caller who wanted to branch on the refusal had to parse it.
"""
from pravaha.client import QueryError

# Exactly what pyarrow rendered for a subscription filter naming a column the view lacks.
RAW = (
    "Flight returned invalid argument error, with message: PRV-8002  this view has no column "
    "'no_such_column', so that filter cannot be applied to it. Its columns are [txn_id, user_id, "
    "amount]. A filter that was quietly ignored would leave you receiving everything while "
    "believing you asked for a slice. gRPC client debug context: UNKNOWN:Error received from peer "
    "ipv4:127.0.0.1:29090 {grpc_message:\"PRV-8002  this view ...\", grpc_status:3}. Client context: IOError"
)


def test_the_engines_code_is_an_attribute_not_a_substring():
    error = QueryError(RAW)
    assert error.engine_code == "PRV-8002"
    assert error.code == 1041  # this client's QUERY_REFUSED, as the Java SDK's


def test_the_message_is_the_servers_sentence_without_the_transports_wrapping():
    error = QueryError(RAW)
    assert error.message.startswith("PRV-8002  this view has no column 'no_such_column'")
    assert error.message.endswith("believing you asked for a slice")
    assert "gRPC" not in str(error) and "Flight returned" not in str(error)
    assert "127.0.0.1" not in str(error)


def test_a_failure_with_no_engine_code_says_so_rather_than_inventing_one():
    error = QueryError("the server accepted the registration but said nothing about it")
    assert error.engine_code is None
    assert error.message == "the server accepted the registration but said nothing about it"


def test_this_clients_own_code_is_never_taken_for_the_engines():
    assert QueryError("PRV-1041  refused, see PRV-2050  unbounded").engine_code == "PRV-2050"


def test_a_query_failure_keeps_no_wrapper_whatever_came_before_it():
    error = QueryError("query failed: Flight returned not found error, with message: PRV-4023  'x' is not a registered view")
    assert error.engine_code == "PRV-4023"
    assert error.message == "PRV-4023  'x' is not a registered view"


def test_nothing_answering_is_a_retryable_connect_error_not_a_refusal():
    import pyarrow.flight as flight

    from pravaha.client import ConnectError, _failure

    down = _failure(flight.FlightUnavailableError("failed to connect to all addresses"))
    assert isinstance(down, ConnectError) and down.code == 1040 and down.retryable
    refused = _failure(flight.FlightServerError("PRV-2050  unbounded"))
    assert isinstance(refused, QueryError) and not refused.retryable and refused.engine_code == "PRV-2050"

