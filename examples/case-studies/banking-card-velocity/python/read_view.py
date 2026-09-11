#!/usr/bin/env python3
"""Read the 'card_velocity' view this case study maintains.

    . ../../../sdk/python/.venv/bin/activate
    python3 read_view.py --card-id c-1002        # or whatever this study's key is called

Every value is bound, never interpolated. A bound value cannot be read as SQL -- by the
time it reaches the server the statement is already planned and there is no parser left
for it to reach -- and the server plans a parameterised statement once and reuses it, so
a thousand lookups are one query rather than a thousand.
"""
import argparse
import pathlib
import sys

from pravaha import connect

SQL_DIR = pathlib.Path(__file__).resolve().parent.parent / "sql"


def read(name: str) -> str:
    return (SQL_DIR / name).read_text().strip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="grpc://localhost:9090")
    parser.add_argument("--token", default=None, help="bearer token, if the server requires one")
    parser.add_argument("--key", required=True, help="the key to look up in card_velocity")
    args = parser.parse_args()

    options = None
    if args.token:
        from pravaha.options import ClientOptions

        # allow_insecure_token only because this is a loopback demo. Over a network the SDK
        # refuses to send a credential in clear, and it is right to.
        options = ClientOptions.create(args.url, token=args.token, allow_insecure_token=True)

    with (connect(options=options) if options else connect(args.url)) as client:
        # The parameterised single-key read: file 02 in this study's sql/ directory.
        statement = sorted(p.name for p in SQL_DIR.glob("02-read-*.sql"))[0]
        for row in client.query(read(statement), [args.key]):
            print({name: row[name] for name in row.columns})

        # The grouped summary: file 03, which takes no parameters.
        summary = sorted(p.name for p in SQL_DIR.glob("03-read-*.sql"))[0]
        print("---", summary)
        for row in client.query(read(summary)):
            print({name: row[name] for name in row.columns})


if __name__ == "__main__":
    sys.exit(main())
