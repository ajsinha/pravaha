---
title: TLS everywhere
slug: tls
category: security
order: 50
icon: lock
summary: "Encrypting every connection: the Flight port, the PostgreSQL gateway, the HTTP API, the CLI and SDKs dialling in, and connectors dialling out — and the three option vocabularies that do not agree."
badge: SECURITY
audience: Operators
keywords: [tls, ssl, encryption, pravaha.flight.tls, pravaha.pgwire.tls, "grpc+tls", mtls, certificate, ca, truststore, keystore, hostname verification, sslmode, server.ssl, openssl]
guide: connector-tls
related: [authentication, connector-security, pgwire, client-snippets, configuration]
---

Pravaha makes and accepts connections on several surfaces, and each is encrypted separately. They
are genuinely different problems — the server accepting clients, clients dialling the server,
connectors dialling someone else's database — and one rule holds across all of them:

> **Configuration decides, in both directions.** `tls.enabled: false` turns TLS off even with
> certificate material beside it; a connection a URL spells as TLS is TLS. Nothing infers its way
> past something you wrote down. Inference only fills a silence.

## The surfaces

| Surface | Direction | Configured by | Default |
|---|---|---|---|
| Arrow Flight SQL, port 9090 | clients → node | `pravaha.flight.tls.certificate`, `pravaha.flight.tls.key` | **plaintext** (`grpc+tcp`) |
| PostgreSQL gateway, port 5432 | `psql`/BI tools → node | `pravaha.pgwire.tls.certificate`, `pravaha.pgwire.tls.key` | plaintext |
| HTTP API and Prometheus, port 8080 | clients → node | Spring Boot's own `server.ssl.*` keys | plaintext |
| CLI, Java and Python SDKs | client → node | the endpoint URL (`grpc+tls://`) and the SDK's TLS options | TLS is the SDKs' default; `grpc://` must be spelled out |
| Connectors (Aerospike, Cassandra, the Kafka source and sink) | node → store | the plugin's shared `tls.*` options | off unless configured |
| JDBC source, lookup, sink; postgres-cdc | node → database | the JDBC `url` (`sslmode=verify-full`, ...) | whatever the URL says |

## The node: Flight

```yaml
pravaha:
  flight:
    enabled: true
    host: 0.0.0.0
    port: 9090
    tls:
      certificate: /opt/pravaha/conf/tls/server-chain.pem
      key: /opt/pravaha/conf/tls/server-key.pem
```

Both are PEM files: the certificate chain, and its private key **in PKCS#8** (`BEGIN PRIVATE KEY`).
Without them the transport is `grpc+tcp` and every row, credential and query travels in clear text --
defensible on a loopback socket and nowhere else, because **authentication over an unencrypted
channel hands the bearer token to anyone on the path**. Setting one of the two without the other is
refused, naming the missing half.

**The pair is checked at startup, not at the first handshake.** A certificate and a key that are
each individually valid and do not belong together used to start the node, which then reported
`flight transport=TLS` while every client failed with `tlsv1 alert internal error` — on the client,
with nothing in the server's log (SX-17). It is now `PRV-6104`, before anything binds, naming both
paths. Giving the two files the wrong way round is the same refusal; it used to be a raw Java
exception with no code.

The node reports its transport at startup (`flight transport=TLS`), and advertises a `grpc+tls`
location to clients when a certificate is configured. **The address it advertises is the one it
actually bound**: with `port: 0` — ask the operating system for a free port — it used to hand
clients an endpoint at port `0`, and a TLS node used to advertise `grpc+tcp://` whatever it was
serving (SX-16).

## The node: the PostgreSQL gateway

```yaml
pravaha:
  pgwire:
    enabled: true
    host: 0.0.0.0
    port: 5432
    tls:
      certificate: /opt/pravaha/conf/tls/server-chain.pem
      key: /opt/pravaha/conf/tls/server-key.pem
```

The gateway negotiates TLS on the same port, as PostgreSQL does, and authenticates **after** the
handshake — so a configured certificate always covers the token a client sends as its password:

```bash
PGPASSWORD="$PRAVAHA_TOKEN" psql "host=pravaha.internal port=5432 user=ann sslmode=verify-full sslrootcert=/opt/pravaha/conf/tls/ca.pem"
```

(With authentication on, the token is the password.)

Use `sslmode=verify-full`: `require` encrypts and checks nothing, which protects against a listener
and not against an impostor. See [the PostgreSQL gateway](/help/topics/pgwire).

## The node: the HTTP API

The HTTP surface (`/api/v1/*`, `/actuator/prometheus`) carries the **same bearer token** as Flight,
and Pravaha's own settings do not encrypt it. HTTPS there comes from Spring Boot's standard
`server.ssl.*` properties (keystore, password, type), set by hand. **A node that configures Flight TLS
and stops there has not secured the channel carrying the same credential over HTTP** — configure
both, or keep port 8080 on a network only trusted clients reach.

## Clients: the CLI

The URL decides: `grpc+tls://` is encrypted, `grpc://` is not.

```bash
pravaha queries --url grpc+tls://pravaha.internal:9090 --token "$PRAVAHA_TOKEN"
```

Over `grpc://`, the CLI refuses to send a token at all — before any connection is attempted --
unless `--insecure-token` is typed, which is for a loopback socket or a TLS-terminating sidecar:

```bash
pravaha queries --url grpc://localhost:9090 --token "$PRAVAHA_TOKEN" --insecure-token
```

The CLI has no flag for a CA file: the server's certificate must chain to a CA its Java runtime
already trusts.

## Clients: Python

```python
import os
from pravaha import ClientOptions, TlsOptions, connect

tls = TlsOptions.create(ca_certificate="/opt/pravaha/conf/tls/ca.pem")
options = ClientOptions.create("grpc+tls://pravaha.internal:9090",
                               token=os.environ["PRAVAHA_TOKEN"],
                               tls=tls,
                               http_url="https://pravaha.internal:8080")
with connect(options=options) as client:
    print([q.name for q in client.queries()])
```

| `TlsOptions.create(...)` | Meaning |
|---|---|
| `ca_certificate` | PEM CA bundle to trust |
| `client_certificate`, `client_key` | PEM pair for mutual TLS — both or neither |
| `trust_store`, `trust_store_password`, `trust_store_type` | JKS/PKCS12 alternative to `ca_certificate` |
| `key_store`, `key_store_password`, `key_store_type` | JKS/PKCS12 alternative to the PEM pair |
| `override_hostname` | check the certificate against this name instead — it must still be properly signed |
| `disable_hostname_verification` | turns verification off entirely; refused together with any certificate material |

PEM and keystore forms together are refused; so is TLS material on a `grpc://` endpoint; so is a token
over plaintext unless `allow_insecure_token=True`.

## Clients: Java, and the vocabulary trap

The Java SDK reads its TLS options from a configuration map, and its words differ from the
connectors' — the sharpest edge in this whole area:

| Concept | Connector (`options:` of a plugin) | Java SDK |
|---|---|---|
| Trust a CA (PEM) | `tls.ca` | `tls.ca-certificate` |
| Own certificate | `tls.certificate` | `tls.client-certificate` |
| Own private key | `tls.key` | `tls.client-key` |
| Truststore file | `tls.truststore` | `tls.trust-store` |
| Truststore password | `tls.truststore.password` | `tls.trust-store-password` |
| Keystore file | `tls.keystore` | `tls.key-store` |
| Keystore password | `tls.keystore.password` | `tls.key-store-password` |
| Hostname checking | `tls.verify-hostname` (default `true`) | `tls.disable-hostname-verification-insecure` (default `false`) |

The connector uses a dot before `password`, the SDK a hyphen; the hostname settings are inverses of
each other; both default to the safe answer. The Python SDK is a third vocabulary again (above). An
unrecognised `tls.` option is **refused**, and when it is the other side's spelling, the error names
the right word — silently ignoring it would mean plaintext.

## Connectors dialling out

Aerospike, Cassandra and the Kafka source and sink take the shared `tls.*` options inside the binding's
`options:`; JDBC and postgres-cdc take TLS in the URL and refuse `tls.*` (PRV-5074, PRV-5110). The details — Aerospike's separate `tls.name`,
Cassandra's hostname verification, PostgreSQL's `sslmode` ladder — are on
[connector security](/help/topics/connector-security).

## Material for a test environment

```bash
# A CA.
openssl req -x509 -newkey rsa:4096 -days 3650 -nodes \
  -keyout ca-key.pem -out ca.pem -subj "/CN=pravaha-test-ca"

# A server certificate that passes hostname verification: the SAN is the part people leave out.
openssl req -newkey rsa:4096 -nodes -keyout server-key.pem -out server.csr -subj "/CN=pravaha.internal"
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial \
  -out server.pem -days 825 \
  -extfile <(printf "subjectAltName=DNS:pravaha.internal,DNS:localhost,IP:127.0.0.1")

# PKCS#1 keys ("BEGIN RSA PRIVATE KEY") are refused; convert to PKCS#8.
openssl pkcs8 -topk8 -nocrypt -in server-key.pem -out server-key.pk8.pem
```

## Verifying it is actually on

Configuration saying TLS is on is exactly what this page is about not trusting. Check the wire:

```bash
openssl s_client -connect pravaha.internal:9090 -CAfile ca.pem -servername pravaha.internal </dev/null
```

```text
...
Verify return code: 0 (ok)
```

Anything but `0 (ok)` is a trust problem, not a typo. For a connector, the strongest check is the
negative one: point it at a CA that did **not** sign the store's certificate and confirm the
connection now fails. A connector that still connects was not verifying anything.

## Pitfalls

!!! danger "Pitfall: a certificate and key that do not match"
    A certificate and a key that are each valid but are not a pair let the node start and report
    `flight transport=TLS`; the mismatch surfaces only at the first client handshake. Run the
    `openssl s_client` check above after every certificate change.

!!! warning "Pitfall: Flight encrypted, HTTP not"
    The same token travels to port 8080. Encrypt both, or neither leaves the trusted network.

!!! warning "Pitfall: `sslmode=require`"
    It encrypts and verifies nothing. `verify-full` checks the CA and the host name.

!!! note "Between nodes"
    Mutual TLS between nodes is not built: a standby talks to a directory, not to its primary, so
    there is no node-to-node channel to secure yet.

## Where next

- [Connector security](/help/topics/connector-security) — `tls.*` per store
- [Authentication](/help/topics/authentication) — the token TLS protects
- [The PostgreSQL gateway](/help/topics/pgwire)
