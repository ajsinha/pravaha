"""Run the console.

    pravaha-console --engine grpc://localhost:9090 --port 8080
"""
from __future__ import annotations

import argparse

import uvicorn

from pravaha_console.app import create_app
from pravaha_console.engine import Engine


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", default="grpc://localhost:9090", help="the Pravaha server to talk to")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--token", default=None, help="bearer token, if the engine requires one")
    args = parser.parse_args()

    # Bound to loopback by default. An operator console reaches a whole cluster's state and
    # has no authentication of its own; making it reachable is a decision somebody should
    # take on purpose, with something in front of it.
    uvicorn.run(create_app(Engine(args.engine, args.token)), host=args.host, port=args.port)


if __name__ == "__main__":
    main()
