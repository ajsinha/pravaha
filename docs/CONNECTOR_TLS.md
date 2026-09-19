# TLS

Every encrypted connection Pravaha makes or accepts, and how configuration turns each one on.

There are three surfaces and they are genuinely different problems. A **connector** dials out to
someone else's database. The **server** accepts connections from clients. An **SDK** dials the
server. This document covers all three, because the person configuring them is usually the same
person on the same afternoon, and because the three vocabularies do not agree — which is a trap
with its own section below.

One rule holds across all of them, and it is the rule the rest of this document is in service of:

> **Configuration decides, in both directions.** Writing `tls.enabled: false` turns TLS off even
> when certificate material is sitting right beside it. Nothing infers its way past something you
> wrote down. Inference only fills a silence.

---

## 1. The shape of a TLS configuration

Every surface answers the same four questions, whatever it calls them:

| Question | What it means | When you need it |
| --- | --- | --- |
| **Is TLS on?** | Encrypt this connection at all | Always |
| **Whom do I trust?** | Which CA signed the certificate the other end presents | Always, unless the CA is already in the JVM's default trust store |
| **Who am I?** | The certificate and private key this end presents | Only for mutual TLS (mTLS), where the far end authenticates you by certificate |
| **Must the name match?** | Whether the certificate has to belong to the host being dialled | Always — and the safe answer is the default |

That last one is the one people skip, and it is the one that matters. Encryption without name
checking protects you from a passive listener and not at all from an active one: anything holding
a certificate your client trusts can answer for any host. A connection like that is
indistinguishable from a correct one until someone is actually attacking you.

---

## 2. Connectors

A connector is a plugin dialling out — Aerospike, Cassandra, Kafka, a JDBC database. The shared options
live in `pravaha-api/src/main/java/com/ash/messaging/pravaha/api/plugin/PluginTls.java` and every
connector that can take an `SSLContext` reads exactly the same ones.

### 2.1 The options

All are nested under the plugin's `options:` block, and all are prefixed `tls.`

| Option | Meaning |
| --- | --- |
| `tls.enabled` | `true` / `false`. Decides in both directions. Omit it to let material infer. |
| `tls.ca` | PEM file holding the CA certificate chain to trust. |
| `tls.certificate` | PEM file holding this client's certificate chain, for mTLS. |
| `tls.key` | PEM file holding the matching private key (PKCS#8). |
| `tls.truststore` | JKS or PKCS12 file, as an alternative to `tls.ca`. |
| `tls.truststore.password` | Its password. |
| `tls.truststore.type` | `JKS` or `PKCS12`. Guessed from the file extension when omitted. |
| `tls.keystore` | JKS or PKCS12 file, as an alternative to `tls.certificate` + `tls.key`. |
| `tls.keystore.password` | Its password. |
| `tls.keystore.type` | `JKS` or `PKCS12`. Guessed from the extension when omitted. |
| `tls.verify-hostname` | `true` / `false`, default `true`. Read §1 before you change it. |

**PEM and keystore forms are alternatives, not layers.** Setting both `tls.ca` and
`tls.truststore` is refused rather than silently resolved by precedence, because a precedence rule
is a thing you have to already know in order to read the file correctly.

**Half a pair is refused naming the missing half.** `tls.certificate` without `tls.key` does not
produce a connection without a client identity; it produces an error that says which one is
missing. A client that quietly proceeds with half an identity fails later, somewhere else, with a
message about something unrelated.

### 2.2 Enablement, precisely

`tls.enabled` is consulted first and honoured in both directions. If it is absent, TLS is on when
any of `tls.ca`, `tls.certificate`, `tls.key`, `tls.truststore` or `tls.keystore` is set.

Setting `tls.ca` and nothing else therefore works, and is meant to: an operator who pointed at a
CA file has plainly asked for TLS, and a connection that stayed in plaintext because a second flag
was missing would be the worst kind of quiet.

The inverse is equally deliberate. `tls.enabled: false` beside a `tls.ca` you have stopped using
leaves the connection in plaintext. That is what you wrote down.

### 2.3 An unrecognised option is an error

Any `tls.` option a connector does not recognise is refused. This is not pedantry. An option that
is not understood is not applied, so it switches nothing on, inference finds no material, and the
connector opens in plaintext — under a configuration file that reads as though it is encrypted.
Nobody goes looking for a problem their own configuration says they do not have.

When the unrecognised option is the SDK's spelling of something a connector *does* have a word
for, the error names that word. See §5.

---

## 3. Per-store specifics

The shared options are the same everywhere. What each driver does with them is not.

### 3.1 Aerospike — the certificate name is a second, separate thing

Aerospike does **not** check the certificate against the host you dialled. It checks it against a
configured name, the `tls-name` in the server's `aerospike.conf`. So TLS here needs two settings,
not one, and the second has no default that could be correct:

```yaml
streams:
  - name: positions
    plugin: aerospike
    options:
      hosts: "db-1.internal:4333,db-2.internal:4333"
      namespace: trading
      set: positions
      schema: "position_id:INT64,book:STRING,quantity:INT64,as_of:TIMESTAMP"
      tls.enabled: true
      tls.name: aerospike-cluster        # the tls-name from aerospike.conf. Usually NOT a hostname.
      tls.ca: /etc/pravaha/tls/aerospike-ca.pem
```

Set `tls.enabled` without `tls.name` and Pravaha refuses at configuration time, saying which half
is missing. The alternative was letting the Aerospike client fail later at connect with a message
that names neither half.

Note the port: an Aerospike cluster with TLS on usually listens on **4333**, not 3000.

### 3.2 Cassandra — hostname verification is not automatic

The obvious way to give the DataStax driver an `SSLContext` encrypts the connection and performs
**no hostname verification at all**. Pravaha does not use it. `tls.verify-hostname` is passed to
the driver in the form that honours it, and defaults to `true`.

```yaml
streams:
  - name: trades
    plugin: cassandra
    options:
      contact.points: "cass-1.internal:9042,cass-2.internal:9042"
      keyspace: market
      table: trades
      local.datacenter: dc1
      schema: "trade_id:INT64,symbol:STRING,quantity:INT64,ingested_at:TIMESTAMP"
      partition.key: trade_id
      event.time: ingested_at
      user: pravaha
      password: ${CASSANDRA_PASSWORD}
      tls.enabled: true
      tls.truststore: /etc/pravaha/tls/cassandra-truststore.p12
      tls.truststore.password: ${TRUSTSTORE_PASSWORD}
```

### 3.3 JDBC — TLS lives in the URL, and `tls.*` is refused

A JDBC driver cannot be handed an `SSLContext`. `DriverManager` takes a URL and a property bag,
and every driver spells TLS its own way. So the shared `tls.*` options are **refused** on a JDBC
connector rather than accepted and ignored — accepting them would leave a plaintext socket behind
a configuration that claims otherwise.

Put it in the URL:

```yaml
streams:
  - name: orders
    plugin: jdbc
    options:
      url: "jdbc:postgresql://pg.internal:5432/sales?ssl=true&sslmode=verify-full&sslrootcert=/etc/pravaha/tls/pg-ca.pem"
      table: orders
      watermark.column: updated_at
      user: pravaha
      password: ${PG_PASSWORD}
```

`sslmode` is the setting that matters, and PostgreSQL's ladder is worth knowing:

| `sslmode` | Encrypted | CA checked | Hostname checked |
| --- | --- | --- | --- |
| `require` | yes | **no** | **no** |
| `verify-ca` | yes | yes | **no** |
| `verify-full` | yes | yes | yes |

`require` is the one that looks secure in a config file and is not. Use `verify-full`.

MySQL's equivalent is `sslMode=VERIFY_IDENTITY` with `trustCertificateKeyStoreUrl`. Oracle and SQL
Server each have their own spelling; check the driver's documentation.

If a plaintext JDBC connection is deliberate — a database on a loopback socket, say — write
`tls.enabled: false`. That is accepted, because it is a statement rather than a mistake.

The same holds for every JDBC connector: the `jdbc` source, the JDBC lookup, and the `jdbc-sink`
under `pravaha.sinks.<name>` — each refuses `tls.*` with `PRV-5074` and takes its TLS in `url`.

### 3.4 Kafka — the shared options, mapped to Kafka's `ssl.*`

The Kafka client takes files and properties, not an `SSLContext`, so `kafka-sink` maps the shared
options onto Kafka's own. `PluginTls` still reads them first, so everything in §2 holds: an unknown
`tls.*` option, half a certificate pair, both forms of one thing, an unreadable file or a PKCS#1 key
is refused (`PRV-5100`) with the same words every other connector uses.

| Shared option | Kafka property |
| --- | --- |
| `tls.ca` | `ssl.truststore.type=PEM`, `ssl.truststore.location` |
| `tls.truststore` (+ `.password`, `.type`) | `ssl.truststore.location`, `.password`, `.type` (guessed from the extension) |
| `tls.certificate` + `tls.key` | `ssl.keystore.type=PEM`, with the two files' contents as `ssl.keystore.certificate.chain` and `ssl.keystore.key` |
| `tls.keystore` (+ `.password`, `.type`) | `ssl.keystore.location`, `.password`, `.type`, and `ssl.key.password` |
| `tls.verify-hostname` | `ssl.endpoint.identification.algorithm`: `https` (the default), or empty for `false` |

`security.protocol` follows: `SSL` with TLS on, `SASL_SSL` when `user` and `password` are set too,
`SASL_PLAINTEXT` for SCRAM without TLS. SASL `PLAIN` without TLS is refused — it sends the password
as it is. `kafka.ssl.*` and `kafka.security.protocol` are refused, so TLS has one spelling.

```yaml
pravaha:
  sinks:
    spend_topic:
      plugin: kafka-sink
      options:
        bootstrap.servers: "kafka-1.internal:9093"
        topic: spend-by-user
        schema: "user_id:STRING,total:INT64"
        key.columns: user_id
        tls.ca: /etc/pravaha/tls/kafka-ca.pem
        tls.certificate: /etc/pravaha/tls/pravaha.crt   # mTLS, if the listener asks for a client certificate
        tls.key: /etc/pravaha/tls/pravaha.key
```

Tested: the mapping, and that Kafka's own loader accepts the result (the producer builds its TLS
engine in its constructor). Not tested here: a handshake with a TLS listener — the broker tests run
in plaintext.

---

## 4. The server and the SDK

### 4.1 The server

Pravaha's own listeners are configured in `pravaha-server/src/main/resources/application.yaml`.

```yaml
pravaha:
  flight:
    enabled: true
    host: 0.0.0.0
    port: 9090
    tls:
      certificate: /etc/pravaha/tls/server-chain.pem
      key: /etc/pravaha/tls/server-key.pem
```

Without those two the Arrow Flight transport is `grpc+tcp` and every row, credential and query
travels in clear text. That is defensible on a loopback socket and nowhere else: **authentication
over an unencrypted channel hands the bearer token to anyone on the path.**

A half-set pair is refused naming the missing half. This is not a hypothetical politeness — an
earlier version of the Flight server read a private key into a field, served plaintext anyway, and
advised the operator to set keys they had already half set.

The PostgreSQL wire gateway takes the same two settings under `pravaha.pgwire.tls`, and `psql`'s
`sslmode=require` negotiates over the same port.

### 4.2 The SDKs

The Java SDK resolves TLS from the same kind of configuration map, and its `tls.enabled` likewise
decides in both directions and is never overridden by inference. An endpoint URL written
explicitly as `grpc+tls://` or `grpc://` always wins, because it is the most specific thing the
caller said.

Its vocabulary is richer than the connector side, and different. See the table in §5.

---

## 5. The three vocabularies do not agree

This is the sharpest edge in this document, so it gets its own section.

| Concept | Connector | Java SDK |
| --- | --- | --- |
| Trust a CA (PEM) | `tls.ca` | `tls.ca-certificate` |
| Own certificate | `tls.certificate` | `tls.client-certificate` |
| Own private key | `tls.key` | `tls.client-key` |
| Truststore file | `tls.truststore` | `tls.trust-store` |
| Truststore password | `tls.truststore.password` | `tls.trust-store-password` |
| Keystore file | `tls.keystore` | `tls.key-store` |
| Keystore password | `tls.keystore.password` | `tls.key-store-password` |
| Hostname checking | `tls.verify-hostname` (default `true`) | `tls.disable-hostname-verification-insecure` (default `false`) |

Note the connector uses a dot before `password`; the SDK uses a hyphen. Note the hostname settings
are inverses of each other. Both default to the safe answer.

Guessing wrong here is the expected mistake, not a careless one. It is handled the only way it
safely can be: **an unrecognised `tls.` option is refused, and when it is the other surface's
spelling of something this side knows, the error names the right word.** It is not silently
ignored, because silently ignoring it means plaintext.

The Python SDK is a third vocabulary again — `tls_root_certs`, `cert_chain` — following the
convention of the Arrow Flight library it wraps rather than Pravaha's own.

---

## 6. Generating material for a test environment

Enough to get a working encrypted connection you can then verify.

```bash
# A CA.
openssl req -x509 -newkey rsa:4096 -days 3650 -nodes \
  -keyout ca-key.pem -out ca.pem -subj "/CN=pravaha-test-ca"

# A server certificate that will actually pass hostname verification. The SAN is the part
# people leave out, and a certificate without one fails verification no matter what the CN says.
openssl req -newkey rsa:4096 -nodes -keyout server-key.pem -out server.csr \
  -subj "/CN=pravaha.internal"
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial \
  -out server.pem -days 825 \
  -extfile <(printf "subjectAltName=DNS:pravaha.internal,DNS:localhost,IP:127.0.0.1")

# A PKCS12 truststore, for the keystore form of the same trust decision.
keytool -importcert -noprompt -alias pravaha-test-ca -file ca.pem \
  -keystore truststore.p12 -storetype PKCS12 -storepass changeit
```

Two notes from having got these wrong:

- **Private keys must be PKCS#8.** A key whose PEM header says `BEGIN RSA PRIVATE KEY` is PKCS#1
  and is rejected with a message saying so. Convert it:
  `openssl pkcs8 -topk8 -nocrypt -in old.pem -out new.pem`.
- **`keytool -genkeypair` into a JKS store prompts for a key password** and will hang a script
  that is not expecting it. Pass `-keypass` explicitly, or use `-storetype PKCS12`.

---

## 7. Verifying it is actually on

Configuration saying TLS is on is what this whole document is about not trusting. Check the wire.

```bash
# What the server presents, and whether the chain validates.
openssl s_client -connect pravaha.internal:9090 -CAfile ca.pem -servername pravaha.internal

# Look for: "Verify return code: 0 (ok)". Anything else is a trust problem, not a typo.
```

For a connector, the strongest check is the negative one: point it at a CA that did **not** sign
the database's certificate and confirm it now fails. A connector that still connects was not
verifying anything.

---

## See also

- [CONNECTORS.md](CONNECTORS.md) — the plugin SPI, the TCK, and what each connector can do
- [CONTINUOUS_QUERIES.md](CONTINUOUS_QUERIES.md) — full worked configuration for every stream type
