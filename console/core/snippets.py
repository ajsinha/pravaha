"""Copy-paste client code for a view, in every client Pravaha ships.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The application developer's whole job on the console is "how do I read this from my
service?" (design 23.2). The answer is a snippet they can paste and run, for the view and
key they are looking at -- not a link to a reference page and an exercise left to them.

Generated rather than templated by hand in the page, so each language's quoting lives in
one place and is tested: a snippet that breaks on a view named ``o'brien`` or a key value
with a quote in it is a snippet that teaches somebody to write an injection.

**No secret ever appears in a snippet.** The token is read from ``PRAVAHA_TOKEN`` in every
language, because a snippet is exactly the thing that gets pasted into a chat.
"""
from __future__ import annotations

import json
import re
import shlex
from urllib.parse import urlsplit

#: A bare SQL identifier, which is what a view or column name must be to appear unquoted.
IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


class SnippetError(ValueError):
    """The view or column name cannot be put into SQL safely."""


def require_identifier(name: str, what: str = "name") -> str:
    if not IDENTIFIER.match(name or ""):
        raise SnippetError(
            f"'{name}' is not a plain SQL identifier; a {what} must be letters, digits and "
            "underscores, starting with a letter")
    return name


def _typed(value: str) -> int | float | str:
    """A key typed into a form, as the value it most likely is.

    Numbers stay numbers: quoting 40 compares a string to an integer and silently matches
    nothing (ADR-032).
    """
    text = value.strip()
    if re.fullmatch(r"-?\d+", text):
        return int(text)
    if re.fullmatch(r"-?\d+\.\d+", text):
        return float(text)
    return value


def _sql_literal(value: float | str) -> str:
    if isinstance(value, (int, float)):
        return repr(value)
    return "'" + str(value).replace("'", "''") + "'"


def _java_string(value: str) -> str:
    return json.dumps(value)  # JSON string escaping is valid Java string escaping for these


def _java_literal(value: float | str) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return f"{value}L" if abs(value) > 2**31 - 1 else str(value)
    if isinstance(value, float):
        return repr(value)
    return _java_string(value)


def pgwire_parts(address: str) -> tuple[str, str]:
    """``host:port`` for psql, from a configured ``host:port`` or ``postgresql://`` URL."""
    text = address.strip() or "localhost:5432"
    if "://" in text:
        parts = urlsplit(text)
        return parts.hostname or "localhost", str(parts.port or 5432)
    host, _, port = text.partition(":")
    return host or "localhost", port or "5432"


def snippets(view: str, *, engine_url: str, pgwire: str = "localhost:5432",
             key_column: str | None = None, key_value: str | None = None,
             columns: list[str] | None = None) -> dict[str, dict[str, str]]:
    """Every client's point read and subscription for one view.

    Returns ``{client: {"label", "language", "read", "subscribe"}}``. With no key, the read
    is a bounded scan, because that is what somebody looking at a view without a key in
    mind wants to see first.
    """
    require_identifier(view, "view name")
    if key_column:
        require_identifier(key_column, "column name")
    projection = ", ".join(require_identifier(c, "column name") for c in columns) if columns else "*"
    keyed = bool(key_column) and key_value is not None and str(key_value) != ""
    value: float | str = _typed(str(key_value)) if keyed else ""

    where_param = f" WHERE {key_column} = ?" if keyed else ""
    sql_param = f"SELECT {projection} FROM {view}{where_param}"
    sql_literal = (f"SELECT {projection} FROM {view} WHERE {key_column} = {_sql_literal(value)}"
                   if keyed else f"SELECT {projection} FROM {view}")
    host, port = pgwire_parts(pgwire)

    # A token over plaintext is refused by both SDKs unless asked for by name, so a snippet
    # for a grpc:// engine connects without one and says how to add it over TLS.
    tls = "://" not in engine_url or engine_url.startswith(("grpc+tls://", "grpcs://", "https://"))

    # ---- Java
    java_args = f", {_java_literal(value)}" if keyed else ""
    java_filter = (f"Map.of({_java_string(str(key_column))}, {_java_string(str(value))})"
                   if keyed else "Map.of()")
    if tls:
        java_imports = ("import com.ash.messaging.pravaha.sdk.ClientOptions;\n"
                        "import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;\n")
        java_connect = (f"ClientOptions options = ClientOptions.builder({_java_string(engine_url)})\n"
                        "        .token(System.getenv(\"PRAVAHA_TOKEN\"))\n"
                        "        .build();\n")
        java_client = "PravahaFlightClient.connect(options)"
    else:
        java_imports = "import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;\n"
        java_connect = ("// Plaintext grpc:// carries no token. Over grpc+tls:// build ClientOptions\n"
                        "// with .token(System.getenv(\"PRAVAHA_TOKEN\")) instead.\n")
        java_client = f"PravahaFlightClient.connect({_java_string(engine_url)})"
    java_read = f"""{java_imports}import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.Row;

{java_connect}try (PravahaFlightClient client = {java_client};
     QueryResult result = client.query({_java_string(sql_param)}{java_args})) {{
    for (Row row : result) {{
        System.out.println(row);
    }}
}}"""
    java_subscribe = f"""import java.util.Map;
{java_imports}import com.ash.messaging.pravaha.sdk.flight.Row;

{java_connect}PravahaFlightClient client = {java_client};
// One callback per commit. weight() is +1 for a row appearing and -1 for one withdrawn.
var subscription = client.subscribe({_java_string(view)}, {java_filter}, batch -> {{
    for (Row row : batch) {{
        System.out.println(row.weight() + " " + row);
    }}
}});
// ... subscription.close(); client.close(); when done"""

    # ---- Python
    py_params = f", [{value!r}]" if keyed else ""
    py_filter = f", {{{key_column!r}: {str(value)!r}}}" if keyed else ""
    if tls:
        py_connect = ("import os\nfrom pravaha import connect\nfrom pravaha.options import ClientOptions\n\n"
                      f"options = ClientOptions.create({engine_url!r}, "
                      "token=os.environ.get(\"PRAVAHA_TOKEN\"))\n"
                      "with connect(options=options) as client:\n")
    else:
        py_connect = ("from pravaha import connect\n\n"
                      "# Plaintext grpc:// carries no token; over grpc+tls:// pass\n"
                      "# options=ClientOptions.create(url, token=os.environ[\"PRAVAHA_TOKEN\"]).\n"
                      f"with connect({engine_url!r}) as client:\n")
    python_read = f"""{py_connect}    for row in client.query({sql_param!r}{py_params}):
        print(row.to_dict())"""
    python_subscribe = f"""{py_connect}    # One list per commit. row.weight is +1 appearing, -1 withdrawn.
    for batch in client.subscribe({view!r}{py_filter}):
        for row in batch:
            print(row.weight, row.to_dict())"""

    # ---- psql (pgwire): no parameters in an interactive session, so a quoted literal.
    psql_read = ("# The PostgreSQL gateway is off by default: pravaha.pgwire.enabled=true on the engine.\n"
                 f"PGPASSWORD=\"$PRAVAHA_TOKEN\" psql \"host={host} port={port} dbname=pravaha\" \\\n"
                 f"  -c {shlex.quote(sql_literal)}")
    psql_subscribe = ("-- The PostgreSQL gateway answers reads only; a subscription is Flight.\n"
                      "-- Use the CLI, the Java SDK or the Python SDK to watch changes.")

    # ---- CLI
    cli_token = " --token \"$PRAVAHA_TOKEN\"" if tls else ""
    cli_params = f" --params {shlex.quote(str(value))}" if keyed else ""
    cli_read = (f"pravaha query --url {shlex.quote(engine_url)}{cli_token} \\\n"
                f"  --sql {shlex.quote(sql_param)}{cli_params}")
    cli_filter = f" --filter {shlex.quote(f'{key_column}={value}')}" if keyed else ""
    cli_subscribe = (f"pravaha subscribe --url {shlex.quote(engine_url)}{cli_token} \\\n"
                     f"  --view {shlex.quote(view)}{cli_filter}")

    return {
        "java": {"label": "Java SDK", "language": "java", "read": java_read,
                 "subscribe": java_subscribe},
        "python": {"label": "Python SDK", "language": "python", "read": python_read,
                   "subscribe": python_subscribe},
        "psql": {"label": "psql (pgwire)", "language": "shell", "read": psql_read,
                 "subscribe": psql_subscribe},
        "cli": {"label": "CLI", "language": "shell", "read": cli_read,
                "subscribe": cli_subscribe},
    }
