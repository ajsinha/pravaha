"""A webhook receiver for tutorial 10: prints each alert notification Pravaha sends, and checks its signature.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

    python catcher.py --port 18099 --secret-file "$PRAVAHA_HOME/conf/hook.secret" --log hooks.log

Each POST is one notification. The body is JSON; the headers carry what a receiver needs to trust and
de-duplicate it: ``X-Pravaha-Signature`` is ``sha256=`` and the HMAC-SHA256, under the shared secret, of
``<X-Pravaha-Timestamp>.<body>``; ``Idempotency-Key`` is the same on every attempt to deliver the same
notification, so a receiver that must act once acts on the first and ignores the rest. One line per
notification goes to ``--log`` (and standard output): what happened, to which key, and whether the
signature held. Standard library only.
"""

import argparse
import hashlib
import hmac
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=18099)
    parser.add_argument("--secret-file", required=True)
    parser.add_argument("--log", default="hooks.log")
    args = parser.parse_args()
    secret = Path(args.secret_file).read_bytes().strip()
    log = Path(args.log)
    seen: set = set()

    class Hook(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802 -- the handler's name is http.server's
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            stamp = self.headers.get("X-Pravaha-Timestamp", "")
            expected = "sha256=" + hmac.new(secret, stamp.encode() + b"." + body, hashlib.sha256).hexdigest()
            signed = hmac.compare_digest(expected, self.headers.get("X-Pravaha-Signature", ""))
            key = self.headers.get("Idempotency-Key", "")
            repeat = key in seen
            seen.add(key)
            # Refuse what is not signed with our secret; accept a repeat, and do nothing with it.
            self.send_response(200 if signed else 401)
            self.end_headers()
            n = json.loads(body)
            row = ", ".join(f"{k}={v}" for k, v in n["row"].items())
            line = (f"{n['kind']:<9} {n['summary']}  [{row}]  episode {n['episode']}"
                    f"  signature {'ok' if signed else 'BAD'}{'  (a repeat: ignored)' if repeat else ''}")
            with log.open("a", encoding="utf-8") as out:
                out.write(line + "\n")
            print(line, flush=True)

        def log_message(self, *_: object) -> None:  # quiet: one line per notification is enough
            pass

    HTTPServer(("127.0.0.1", args.port), Hook).serve_forever()


if __name__ == "__main__":
    main()
