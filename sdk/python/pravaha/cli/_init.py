"""``pravaha init [DIR]``: a starter project that runs -- a stream, the file that feeds it, a first
continuous query, and a compose file for the published engine image.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The project is configured the way a node is (docs/guides/QUICKSTART.md, step 4): the stream under
``pravaha.streams`` and what feeds it under ``pravaha.sources`` in the node's ``conf/application.yaml``,
read from ``$PRAVAHA_HOME/conf`` (docs/operations/RUNNING_IN_DOCKER.md). The query is a ``.sql`` file
registered with ``pravaha register``, because a node registers queries over Flight, not from its
configuration. Nothing outside DIR is written: the context the project's node wants is printed as a
``pravaha context add`` command, never saved into the user's configuration.
"""

from __future__ import annotations

import pathlib
import re

import pravaha
from pravaha.cli._common import Context
from pravaha.cli._scaffold import Scaffold, emit, slug

HTTP = "http://localhost:18080"
FLIGHT = "grpc://localhost:19090"
CONSOLE = "http://localhost:17070"
SCHEMA = "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
QUERY_NAME = "spend_per_minute"
QUERY_KEYS = "0,1"

QUERY_SQL = """\
-- Each user's completed spend, per minute of event time. Registered with
--   pravaha register --name spend_per_minute --sql-file queries/spend_per_minute.sql --keys 0,1
-- The key is (user_id, window_start): one row per user per minute, which is what makes the state
-- bounded -- a minute closes when the stream's watermark passes its end and is then final.
SELECT user_id, window_start, window_end, SUM(amount) AS spend, COUNT(*) AS txns
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
WHERE status = 'COMPLETED'
GROUP BY user_id, window_start, window_end
"""

SAMPLE_ROWS = (
    (1, "alice", 500, "COMPLETED", "2026-10-01T09:00:05Z"),
    (2, "bob", 50, "COMPLETED", "2026-10-01T09:00:20Z"),
    (3, "carol", 900, "PENDING", "2026-10-01T09:00:41Z"),
    (4, "alice", 120, "COMPLETED", "2026-10-01T09:00:55Z"),
    (5, "dave", 150, "COMPLETED", "2026-10-01T09:01:10Z"),
    (6, "bob", 75, "COMPLETED", "2026-10-01T09:01:30Z"),
    (7, "alice", 30, "FAILED", "2026-10-01T09:01:45Z"),
    (8, "carol", 300, "COMPLETED", "2026-10-01T09:01:58Z"),
    (9, "frank", 1200, "COMPLETED", "2026-10-01T09:02:15Z"),
    (10, "bob", 60, "COMPLETED", "2026-10-01T09:02:40Z"),
    (11, "dave", 45, "COMPLETED", "2026-10-01T09:03:20Z"),
    (12, "alice", 80, "COMPLETED", "2026-10-01T09:03:50Z"),
)


def image_tag() -> str:
    """The engine image tag that matches this CLI: its version, or ``latest`` from a source tree."""
    version = pravaha.__version__
    return version if re.fullmatch(r"\d+\.\d+\.\d+(?:[-.][A-Za-z0-9.]+)?", version) else "latest"


def application_yaml() -> str:
    return f"""\
# The node's configuration: mounted as /opt/pravaha/conf/application.yaml, which the image's
# launcher reads after the jar's defaults (docs/operations/RUNNING_IN_DOCKER.md, "PRAVAHA_HOME").
# Every path is relative to the node's home, /opt/pravaha in the container.

pravaha:
  # The catalog: what a query can be planned against. A stream can be declared with nothing feeding
  # it; the source below is a separate block for that reason.
  streams:
    txn:
      schema: "{SCHEMA}"
      # The column that carries each row's own time. Without it no watermark advances, no window
      # can close, and a windowed query is refused when it is registered (PRV-2002).
      event-time: event_time
      # How late a row may arrive and still count; a number is seconds.
      out-of-orderness: 5

  # What feeds the stream: the CSV in ./data, mounted read-only at data/incoming/.
  sources:
    txn:
      plugin: filesystem
      options:
        path: data/incoming/txn.csv
        schema: "{SCHEMA}"
        # tail -f: rows appended to data/txn.csv while the node runs arrive without a restart.
        follow: true
"""


def compose_yaml(project: str, tag: str) -> str:
    return f"""\
# {project}: one Pravaha node from the published image, and the console behind a profile.
#
#   docker compose up -d                                   # the engine
#   PRAVAHA_PROFILES=dev,users docker compose --profile console up -d   # and the console
#
# Ports are the standard ones, on 127.0.0.1 only: 18080 HTTP (REST API, probes), 19090 Flight SQL
# (the CLI and the SDKs), 17070 the console. Engine state lives in the named volume pravaha-data,
# so `docker compose down` keeps it and `down -v` starts over.

name: {project}

services:
  pravaha-server:
    image: pravaha/pravaha-server:${{PRAVAHA_TAG:-{tag}}}
    restart: unless-stopped
    environment:
      # dev: an open node for one developer on loopback -- every view to every caller. Add `users`
      # (PRAVAHA_PROFILES=dev,users) for sign-in, which the console needs.
      SPRING_PROFILES_ACTIVE: ${{PRAVAHA_PROFILES:-dev}}
    ports:
      - "127.0.0.1:18080:18080"
      - "127.0.0.1:19090:19090"
    volumes:
      - ./conf/application.yaml:/opt/pravaha/conf/application.yaml:ro
      - ./data:/opt/pravaha/data/incoming:ro
      - pravaha-data:/opt/pravaha/data
      - pravaha-logs:/opt/pravaha/logs
    healthcheck:
      # The image's own probe: the JRE base carries no curl or wget.
      test: ["CMD", "bin/pravaha-health", "/actuator/health/readiness"]
      interval: 10s
      timeout: 3s
      start_period: 60s
      retries: 6
    stop_grace_period: 60s

  pravaha-console:
    profiles: [console]
    image: pravaha/pravaha-console:${{PRAVAHA_TAG:-{tag}}}
    restart: unless-stopped
    depends_on:
      pravaha-server:
        condition: service_healthy
    environment:
      PRAVAHA_ENGINE: grpc://pravaha-server:19090
      PRAVAHA_ENGINE_HTTP: http://pravaha-server:18080
    ports:
      - "127.0.0.1:17070:17070"

volumes:
  pravaha-data: {{}}
  pravaha-logs: {{}}
"""


def sample_csv() -> str:
    return "".join(",".join(str(v) for v in row) + "\n" for row in SAMPLE_ROWS)


def context_command(name: str) -> str:
    return f"pravaha context add {name} --url {FLIGHT} --http {HTTP} --use"


def readme(project: str, name: str) -> str:
    return f"""\
# {project}

A Pravaha starter project made by `pravaha init`: one stream (`txn`), the CSV that feeds it, and a
continuous query that keeps each user's completed spend per minute current.

| File | What it is |
|---|---|
| `conf/application.yaml` | the node's configuration: the `txn` stream and the file source feeding it |
| `data/txn.csv` | twelve sample transactions; append lines and they arrive (`follow: true`) |
| `queries/{QUERY_NAME}.sql` | the continuous query, registered with `pravaha register` |
| `docker-compose.yml` | the engine image on 18080/19090, the console (profile `console`) on 17070 |
| `.pravaha` | the connection this project's node wants, for `pravaha context add` |

## Next

```bash
docker compose up -d
pravaha register --name {QUERY_NAME} --sql-file queries/{QUERY_NAME}.sql --keys {QUERY_KEYS}
pravaha query --sql "SELECT * FROM {QUERY_NAME}"
```

The last minute of the sample stays open until a later row moves the watermark past its end:
append one (`echo '13,bob,10,COMPLETED,2026-10-01T09:05:00Z' >> data/txn.csv`) and ask again, or
watch it change with `pravaha subscribe --view {QUERY_NAME}`.

To save this node as a named connection (once):

```bash
{context_command(name)}
```

## The console

The console signs people in against the engine's users, so the engine needs the `users` profile
beside `dev`:

```bash
PRAVAHA_PROFILES=dev,users docker compose --profile console up -d
```

Then open {CONSOLE} and sign in as `admin` with the development password `pravaha-dev-admin`.
With `users` on, the CLI needs a credential too: `pravaha login --user admin --save`.
"""


def dotfile(name: str) -> str:
    return f"""\
# The node this project's docker-compose.yml runs. pravaha does not read this file; save it as a
# named connection with:
#   {context_command(name)}
context={name}
url={FLIGHT}
http={HTTP}
"""


def build(target: pathlib.Path) -> Scaffold:
    project = slug(target.resolve().name) or "pravaha-project"
    tag = image_tag()
    scaffold = Scaffold("a Pravaha starter project")
    scaffold.add("conf/application.yaml", application_yaml(),
                 "the node's configuration: stream txn (pravaha.streams) fed by the CSV "
                 "(pravaha.sources, filesystem)")
    scaffold.add("data/txn.csv", sample_csv(),
                 "12 sample transactions over four minutes of event time")
    scaffold.add(f"queries/{QUERY_NAME}.sql", QUERY_SQL,
                 "the first continuous query: per-user spend per one-minute window")
    scaffold.add("docker-compose.yml", compose_yaml(project, tag),
                 f"pravaha/pravaha-server:{tag} on 18080 and 19090; the console on 17070 "
                 "(profile console)")
    scaffold.add("README.md", readme(project, project), "what is here, and the next three commands")
    scaffold.add(".pravaha", dotfile(project),
                 "the connection this node wants, for `pravaha context add` (not read by pravaha)")
    scaffold.choices = [
        f"image tag {tag}, this CLI's version, so the engine speaks what the CLI expects; "
        "PRAVAHA_TAG overrides it",
        "the dev profile: an open node on 127.0.0.1 for one developer; PRAVAHA_PROFILES=dev,users "
        "for sign-in and the console",
        "a windowed query with an event-time column, because an unwindowed GROUP BY has unbounded "
        "state and is refused (PRV-2050)",
        "the source follows the file (follow: true), so appended lines arrive without a restart",
        "your configuration is untouched: save the connection with the context command below",
    ]
    scaffold.next = [
        f"cd {target}" if target != pathlib.Path(".") else "# in this directory",
        "docker compose up -d",
        f"pravaha register --name {QUERY_NAME} --sql-file queries/{QUERY_NAME}.sql --keys {QUERY_KEYS}",
        f'pravaha query --sql "SELECT * FROM {QUERY_NAME}"',
        context_command(project) + "   # optional: save the connection",
    ]
    scaffold.extra = {"context": context_command(project), "image": f"pravaha/pravaha-server:{tag}",
                      "query": {"name": QUERY_NAME, "sqlFile": f"queries/{QUERY_NAME}.sql",
                                "keys": QUERY_KEYS}}
    return scaffold


def init(ctx: Context) -> int:
    target = pathlib.Path(ctx.arg("directory", "."))
    return emit(ctx, target, build(target))
