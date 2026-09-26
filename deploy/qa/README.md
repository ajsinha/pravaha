# Pravaha on a QA host

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see the LICENSE file in the root of the repository.

One Linux machine with Docker and the compose plugin. Two containers, the engine and the console,
from two images, each configured by its own YAML file. No registry: the images arrive as files in
this bundle.

## Install

```bash
tar xzf pravaha-qa-<version>.tar.gz && cd pravaha-qa-<version>
sha256sum -c SHA256SUMS
sudo ./install.sh --host <the name browsers will use for this machine>
cd /opt/pravaha && sudo docker compose up -d
```

`install.sh` loads both images, writes the tree below, generates the credentials and prints them
once. Open `http://<host>:8090` and sign in with the console password it printed.

## Everything is under /opt/pravaha

```
/opt/pravaha/
  docker-compose.yml
  conf/application.yaml             the ENGINE's configuration
  console/conf/application.yaml     the CONSOLE's configuration
  data/                             registry journal, checkpoints, dead letters, spill; incoming/txn.csv
  logs/                             pravaha-server.log, audit.jsonl
```

Each directory is mounted into its container **at the same path**, so a path in a config file, a
log line or an error message is the path on the host.

## Changing anything

Edit the YAML file, then restart that one service:

```bash
sudo vi /opt/pravaha/conf/application.yaml           && sudo docker compose restart pravaha
sudo vi /opt/pravaha/console/conf/application.yaml   && sudo docker compose restart console
```

Every setting lives in those two files: security and tokens, streams and their sources, sinks,
checkpoints, quotas, the console's password, where it finds the engine. Each file lists what a QA
host would change. The console's **Help → Settings** page lists every key with its default.

Both files are mode 0600, owned by uid 10001 (the containers' user), because they hold credentials.
Running `install.sh` again **never overwrites them**, so an upgrade keeps your edits: a new bundle
brings new images and a new `docker-compose.yml`, and nothing else changes.

If this host already uses 8080, 9090 or 8090, set `PRAVAHA_HTTP_PORT`, `PRAVAHA_FLIGHT_PORT` or
`PRAVAHA_CONSOLE_PORT` in `/opt/pravaha/.env`. These change only the published port on the host.

## Credentials

| What | Where it is kept | Used by |
|---|---|---|
| Console password | `console/conf/application.yaml`, `console.password` | people, at the sign-in page |
| Console → engine token | the key of the `id: console` entry in `conf/application.yaml`, **and** `engine.token` in the console's file | the console; change both together |
| QA token | the key of the `id: qa` entry in `conf/application.yaml` | the CLI and the SDKs |

To add a person, add an entry under `pravaha.security.tokens` and restart the engine. The map key
is the secret. `id` is the name recorded in the audit trail and the registry.

**Flight is plaintext on this host**, and the engine logs a warning saying so at startup. The SDKs
refuse to send a token over plaintext unless you acknowledge it (`PRV-1031`):

```python
from pravaha import connect, ClientOptions
c = connect(options=ClientOptions.create("grpc://<host>:9090", token="<QA token>",
                                         allow_insecure_token=True))
```

To serve TLS instead, set `pravaha.flight.tls.certificate` and `.key` to files under
`/opt/pravaha/conf/tls/` and connect with `grpc+tls://`.

## A first question

The engine starts with one stream, `txn`, following `/opt/pravaha/data/incoming/txn.csv`. Register
`SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000` in the console's workbench, then:

```bash
echo '7,u4,hooli,8000,USD,OK,2026-09-26T09:01:26Z' | sudo tee -a /opt/pravaha/data/incoming/txn.csv
```

The view changes. That was the whole product: you asked once, and it keeps answering.

## Integrating

[`docs/PYTHON_API_GUIDE.md`](docs/PYTHON_API_GUIDE.md) in this bundle is the integrator's guide: every
call the Python SDK makes and every REST endpoint, each with a sample that runs against this host as
installed.
