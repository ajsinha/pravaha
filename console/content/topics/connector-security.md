---
title: Connector credentials and TLS
slug: connector-security
category: sources
order: 90
icon: shield-lock
summary: "Credentials kept out of the file with ${ENV} placeholders, the eleven shared tls.* options, and what each store's driver actually verifies — Aerospike's tls.name, Cassandra's and Kafka's hostname checks, JDBC's URL."
badge: SECURITY
audience: Operators
keywords: [tls, ssl, mtls, certificate, truststore, keystore, pem, pkcs12, password, credentials, environment, placeholder, tls.name, sslmode, verify-full, verify-hostname, kafka, sasl, scram, postgres-cdc]
guide: connector-tls#2-connectors
related: [tls, sources-overview, source-aerospike, source-cassandra, source-jdbc, source-kafka, sink-kafka, source-postgres-cdc]
---

Every connector that dials out to a store — the Aerospike, Cassandra, JDBC, postgres-cdc and Kafka
sources, their lookups, and the JDBC, Aerospike and Kafka sinks — needs two things kept right: **who it says it is** (credentials) and **whom it
trusts** (TLS). This page covers both for connectors. The engine's own listeners and the SDKs dialling
them are on [TLS everywhere](/help/topics/tls).

One rule runs through all of it:

> **Configuration decides, in both directions.** `tls.enabled: false` turns TLS off even with
> certificate material sitting beside it. Nothing infers its way past something you wrote down;
> inference only fills a silence.

## Credentials

| Connector | Credential options |
|---|---|
| `aerospike`, `aerospike-lookup`, `aerospike-sink` | `user`, `password` — Aerospike security; ignored if `user` is empty |
| `cassandra` | `user`, `password` |
| `jdbc`, `jdbc-lookup`, `jdbc-sink` | `user`, `password` — passed to the driver as properties; or in the URL, if your driver takes them there |
| `postgres-cdc` | `user`, `password` — a role with `REPLICATION` |
| `kafka`, `kafka-sink` | `user`, `password` and `sasl.mechanism` (`PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512`) — SASL. Both or neither; `PLAIN` without TLS is refused |
| `filesystem`, `feedfile`, `delta` | none — they read what the node's operating-system user can read |

### Keeping secrets out of the file

A server node reads its configuration through Spring, so a value may be a **placeholder** resolved from
the environment (or any other property source) when the node starts:

```yaml
pravaha:
  sources:
    orders:
      plugin: jdbc
      options:
        url: "jdbc:postgresql://db-1.internal:5432/sales?ssl=true&sslmode=verify-full&sslrootcert=/opt/pravaha/conf/tls/pg-ca.pem"
        user: pravaha
        password: "${PRAVAHA_DB_PASSWORD}"
        table: orders
        watermark.column: change_ns
        key.column: order_id
```

```bash
export PRAVAHA_DB_PASSWORD='from-your-secret-store'
bin/pravaha-server --spring.config.additional-location=file:./application.yaml
```

Quote a value that starts with `${`, as above — unquoted, some YAML tools read the brace as the start of
a mapping. `${NAME:default}` supplies a default.

!!! warning "Pitfall: an unset variable is not an error"
    Spring binds a placeholder it cannot resolve as its own text. With `PRAVAHA_DB_PASSWORD` unset, the
    driver is handed the literal string `${PRAVAHA_DB_PASSWORD}` as the password and the store refuses
    it — for JDBC, PRV-5070 at the first registration (or at startup, for a lookup). If a connection
    that worked yesterday fails authentication today, check the environment the node was started in
    before the password.

**What never leaves the node.** The startup log names each binding's option *keys*, never their values
(`sources bound: [orders <- jdbc[url, user, password, table, watermark.column, key.column]]`), and
neither `GET /api/v1/plugins` nor `GET /api/v1/sinks` returns a binding's options — so a password in a
binding reaches no client and no console. The embedded engine takes options as a map from your code,
with no placeholder resolution: resolve secrets before you build it.

## TLS: the shared options

Every connector that can take a Java `SSLContext` — Aerospike and Cassandra — reads exactly the same
options, and so do the Kafka source and sink (`kafka`, `kafka-sink`), which map them onto Kafka's own `ssl.*` properties, all under the binding's `options:` and all prefixed `tls.`:

| Option | Meaning |
|---|---|
| `tls.enabled` | `true` / `false`. Decides in both directions. Omit it to let material infer |
| `tls.ca` | PEM file holding the CA chain to trust |
| `tls.certificate` | PEM file holding this client's certificate chain, for mutual TLS |
| `tls.key` | PEM file holding the matching private key, **PKCS#8** |
| `tls.truststore` | JKS or PKCS12 file — an alternative to `tls.ca` |
| `tls.truststore.password` | Its password |
| `tls.truststore.type` | `JKS` or `PKCS12`; guessed from the extension when omitted |
| `tls.keystore` | JKS or PKCS12 file — an alternative to `tls.certificate` + `tls.key` |
| `tls.keystore.password` | Its password |
| `tls.keystore.type` | `JKS` or `PKCS12`; guessed from the extension when omitted |
| `tls.verify-hostname` | `true` / `false`, default `true` |

**When is TLS on?** `tls.enabled`, if written, decides. If it is absent, TLS is on as soon as any of
`tls.ca`, `tls.certificate`, `tls.key`, `tls.truststore` or `tls.keystore` is set — pointing at a CA file
is plainly asking for TLS, and a connection left in plaintext because a second flag was missing would
be the worst kind of quiet.

Three refusals, each at configuration time and each naming what to fix:

- **Both forms at once** — `tls.ca` with `tls.truststore`, or `tls.certificate` with `tls.keystore` — is
  refused rather than resolved by a precedence rule you would have to know to read the file.
- **Half a pair** — `tls.certificate` without `tls.key` — is refused naming the missing half.
- **An unrecognised `tls.` option** is refused, because an option not understood switches nothing on and
  the connector would open in plaintext under a file that reads as encrypted. When it is the SDK's
  spelling of an option the connector has (`tls.ca-certificate`, `tls.trust-store`,
  `tls.disable-hostname-verification-insecure`, …) the error names the connector's word.

## What each store verifies

### Aerospike — a second name, with no default

Aerospike does not check the server certificate against the host dialled; it checks it against the
**`tls-name`** in the server's `aerospike.conf`. So Aerospike takes one more option, `tls.name`, and TLS
without it is refused with PRV-5083 saying which half is missing. The TLS port is usually 4333.

```yaml
pravaha:
  lookups:
    user_profile:
      plugin: aerospike-lookup
      options:
        hosts: "as-1.internal:4333,as-2.internal:4333"
        namespace: ref
        set: users
        stream: user_profile
        schema: "user_id:STRING,tier:STRING,region:STRING"
        key.bin: user_id
        user: pravaha
        password: "${AEROSPIKE_PASSWORD}"
        tls.enabled: "true"
        tls.name: aerospike-cluster
        tls.ca: /opt/pravaha/conf/tls/aerospike-ca.pem
        tls.certificate: /opt/pravaha/conf/tls/pravaha-client.pem
        tls.key: /opt/pravaha/conf/tls/pravaha-client-key.pem
```

The last two make it mutual TLS: the cluster authenticates Pravaha by certificate.

### Cassandra — hostname verification, actually on

The obvious way to give the DataStax driver an `SSLContext` encrypts and performs **no hostname
verification** — anything holding a certificate this client trusts could answer for any node. The plugin
uses the driver's programmatic engine factory instead, which honours `tls.verify-hostname` (default
`true`). The certificate must carry the node's name in its subject alternative names.

```yaml
pravaha:
  sources:
    orders:
      plugin: cassandra
      options:
        contact.points: "cass-1.internal:9042,cass-2.internal:9042"
        local.datacenter: dc1
        keyspace: sales
        table: orders
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        partition.key: order_id
        user: pravaha
        password: "${CASSANDRA_PASSWORD}"
        tls.truststore: /opt/pravaha/conf/tls/cassandra-truststore.p12
        tls.truststore.password: "${TRUSTSTORE_PASSWORD}"
```

No `tls.enabled` here: the truststore turns TLS on by inference.

### Kafka — the shared options, mapped onto `ssl.*`, and SASL

Kafka's clients take files and properties, not an `SSLContext`, so the Kafka plugin — one mapping
for the source `kafka` and the sink `kafka-sink` alike — checks the shared
`tls.*` options exactly as every other connector does (the same refusals, the same words) and then
maps them: `tls.ca` becomes a PEM truststore, `tls.certificate` and `tls.key` a PEM keystore, a
`tls.truststore` or `tls.keystore` is passed by location, and `tls.verify-hostname` is Kafka's endpoint
identification (`https`, or off). Setting Kafka's own `kafka.ssl.*` or `kafka.security.protocol` is
refused (PRV-5100): the protocol follows from `tls.*` and the credentials — `SSL`, `SASL_SSL`,
`SASL_PLAINTEXT` or `PLAINTEXT`.

```yaml
pravaha:
  sinks:
    open_orders_topic:
      plugin: kafka-sink
      options:
        bootstrap.servers: "kafka-1.internal:9093,kafka-2.internal:9093"
        topic: open-orders
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING"
        key.columns: order_id
        user: pravaha
        password: "${KAFKA_PASSWORD}"
        sasl.mechanism: SCRAM-SHA-512
        tls.ca: /opt/pravaha/conf/tls/kafka-ca.pem
```

The source takes the same options under its own binding:

```yaml
pravaha:
  sources:
    orders:
      plugin: kafka
      options:
        bootstrap.servers: "kafka-1.internal:9093,kafka-2.internal:9093"
        topic: orders
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        user: pravaha
        password: "${KAFKA_PASSWORD}"
        sasl.mechanism: SCRAM-SHA-512
        tls.ca: /opt/pravaha/conf/tls/kafka-ca.pem
```

Its consumers need `Describe` and `Read` on the topic, and `Read` on `monitoring.group` if one is
set. It refuses `kafka.ssl.*`, `kafka.security.protocol` and `kafka.sasl.jaas.config` as the sink does.

`PLAIN` sends the password to the broker as it is, so `sasl.mechanism: PLAIN` (the default when `user`
is set) without TLS is refused; the SCRAM mechanisms never send it and are allowed either way. See
[the Kafka source](/help/topics/source-kafka) and [the Kafka sink](/help/topics/sink-kafka).

### JDBC — TLS lives in the URL, and `tls.*` is refused

A JDBC driver cannot be handed an `SSLContext`; it takes a URL and a bag of properties, and every driver
spells TLS its own way. So the `jdbc` source, `jdbc-lookup` and `jdbc-sink` **refuse** the shared
`tls.*` options with PRV-5074 — accepting them would leave a plaintext socket behind a configuration
that claims otherwise — and `postgres-cdc`, which opens its replication connection from the same
kind of URL, refuses them with PRV-5110. Put TLS in `url`:

| Database | In the URL |
|---|---|
| PostgreSQL | `?ssl=true&sslmode=verify-full&sslrootcert=/path/ca.pem` |
| MySQL | `?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/path/truststore.p12` |
| Oracle, SQL Server | each driver's own properties — see its documentation |

PostgreSQL's `sslmode` ladder is the one to know:

| `sslmode` | Encrypted | CA checked | Hostname checked |
|---|---|---|---|
| `require` | yes | **no** | **no** |
| `verify-ca` | yes | yes | **no** |
| `verify-full` | yes | yes | yes |

`require` is the one that looks secure in a file and is not. Use `verify-full`.

A deliberately plaintext JDBC connection — a database on a loopback socket — is written
`tls.enabled: "false"`, which is accepted because it is a statement rather than a mistake.

### Files and tables on disk

`filesystem`, `feedfile` and `delta` open local paths as the node's operating-system user and have no
TLS options. Protect the directory; secure the transfer that fills it.

## Checking it is really on

Configuration saying TLS is on is exactly what not to trust. Check the wire, and check the negative:

```bash
# What the database presents, and whether the chain validates against your CA.
openssl s_client -connect db-1.internal:5432 -starttls postgres -CAfile /opt/pravaha/conf/tls/pg-ca.pem

# Aerospike's TLS port, with its tls-name as the expected name.
openssl s_client -connect as-1.internal:4333 -CAfile /opt/pravaha/conf/tls/aerospike-ca.pem -servername aerospike-cluster
```

```text
Verify return code: 0 (ok)
```

Anything other than `0 (ok)` is a trust problem, not a typo. Then point the connector at a CA that did
**not** sign the store's certificate and confirm the registration now fails: a connector that still
connects was verifying nothing.

## Pitfalls

!!! danger "Pitfall: `tls.verify-hostname: false` to make an error go away"
    Encryption without a name check protects against a passive listener and not at all against an
    active one: anything holding a certificate your CA signed can answer for any host. Fix the
    certificate's subject alternative names instead.

!!! warning "Pitfall: a PKCS#1 private key"
    A key whose PEM header says `BEGIN RSA PRIVATE KEY` is PKCS#1 and is refused with a message saying
    so. Convert it: `openssl pkcs8 -topk8 -nocrypt -in old.pem -out new.pem`.

!!! note "The SDK spells these differently"
    The connector says `tls.truststore.password`, the Java SDK `tls.trust-store-password`; the
    connector's `tls.verify-hostname` is the inverse of the SDK's
    `tls.disable-hostname-verification-insecure`. Copying one surface's options to the other is refused
    with the right word named, never silently ignored.

## Where next

- [TLS everywhere](/help/topics/tls) — the engine's Flight, HTTP and PostgreSQL listeners, and the SDKs
- [Authentication](/help/topics/authentication) — who may connect to the engine itself
- [Sources overview](/help/topics/sources-overview) — every connector and what it can see
