---
title: Named connections
slug: connections
category: sources
order: 12
icon: plug
summary: "A connection's plugin and options written once, in config/connectors/<name>.yaml, and named by every stream source, lookup and sink that uses it. Managed from Catalog → Connectors: create, edit, test, disable, delete."
keywords: [connection, named connection, connector, config/connectors, "connector:", test connection, secret, "${ENV}", PRV-1063, PRV-1065, PRV-1067, PRV-1068, PRV-1073, enable, disable, plugins, catalog connectors]
audience: Operators
guide: connectors
related: [sources-overview, connector-security, streams, lookups, sinks-overview]
---

A **named connection** is a plugin and its options written once — a Kafka cluster's servers and TLS
settings, a database's URL and user — in a file of its own, `config/connectors/<name>.yaml`. A stream's
source, a lookup or a sink names it (`connector: kafka-prod`) instead of repeating them; its own options
are laid over the connection's, and **the binding wins on a clash**.

```yaml
# config/connectors/kafka-prod.yaml
plugin: kafka
options:
  bootstrap.servers: broker1:9092
  ssl.truststore.password: ${KAFKA_TRUSTSTORE_PASSWORD}
```

## Catalog → Connectors {#the-page}

The page lists the **plugins this node can load** — name, version, what each can be (source, sink,
lookup) and its health — and its **named connections**: each one's plugin, whether it is enabled, its
options with every secret masked by the engine, the bindings that name it, and its file.

- **New connection** and **Edit** are a form over the file: a plugin from the loaded ones, and its
  options as key/value rows. A plugin declares no option list the engine could show, so the keys are
  yours to write — the plugin's page in Help lists them; a plugin registered with a manifest offers its
  settings as suggestions. **Validate** is a dry run that shows the exact file under *Preview YAML*;
  **Save** writes it and the node applies it at once.
- **Test connection** goes through the connection's own plugin: it is configured with the connection's
  options, opened, asked its health and closed, in bounded time (ten seconds). The page shows the
  outcome — `HEALTHY`, `DEGRADED`, `UNHEALTHY`, `FAILED` or `TIMED_OUT` — and how long it took, and
  never an option's value. A source plugin may need its topic or table to open at all; a failure that
  says so has still reached the cluster.
- **Disable** writes `enabled: false`. The **stream sources that name the connection stop** — declared,
  bound, read from nothing, their queries running and receiving nothing — until it is enabled. Lookups
  and sinks that name it keep running: a lookup's table is already open, and a sink that stopped would
  lose the output of the queries writing to it. Disabling is never refused for being in use.
- **Edit and Delete are refused while a binding names the connection** (`PRV-1065`), naming the
  bindings, and nothing is written: a binding took its plugin and options from the connection when it
  was bound, and would not see the change.

## Secrets are references {#secrets}

A key whose last segment names a **password, secret, token or API key** must hold `${ENV_NAME}`, and
the variable is set where the node runs. A literal value is refused (`PRV-1063`) — from the form, the
API and the CLI exactly as in a file written by hand — and the refusal names the setting, never the
value. The form marks such a row before you press Save. A reference to a variable the node does not
have is refused too: the file would not start the node.

## The same, from the CLI and the API {#cli-and-api}

`pravaha connectors create|edit|delete|enable|disable|test`, and `POST|PUT|DELETE
/api/v1/connectors/{name}`, `POST /api/v1/connectors/{name}/enable|disable|test` — each write with
`dryRun=true`. They need the `admin` role or a grant to administer the connection, and each is audited
as `config.connector.<action>` with who made it.

## Codes {#codes}

| Code | Means |
|---|---|
| `PRV-1063` | a literal secret: write `${ENV_NAME}` |
| `PRV-1065` | a binding names the connection, so it cannot be changed or removed now |
| `PRV-1067` | a binding names a connection no file declares |
| `PRV-1068` | a binding names a plugin and a connection whose plugin differs |
| `PRV-1069` | New, for a name a file already declares |
| `PRV-1073` | the connection test failed or ran out of time |
