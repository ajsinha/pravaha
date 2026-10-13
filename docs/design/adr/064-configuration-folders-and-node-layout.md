# ADR-064: a file per stream, lookup, sink and connection, watched; three roots and their conventions

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **built** in the server. The embedded engine reads the inline keys only (no folders, no watching) and takes a data root only when one is written |
| Date | 2026-10-12 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-063 (the AI layer's provider folder, the pattern this follows), ADR-052 (the identity store kept on the data volume), ADR-059 (the catalogue journal), ADR-057 (the alerts journal), ADR-062 (Java 21 or later) |

## Context

A node is described by one file, `application.yaml`. Every stream, source, lookup and sink is a block
in it (`pravaha.streams.<name>`, `pravaha.sources.<name>`, `pravaha.lookups.<name>`,
`pravaha.sinks.<name>`), read once at start. Three things followed:

- **Adding a stream meant editing the node's whole configuration and restarting it.** The only live
  path was `POST /api/v1/streams`, which could not bind a source and was gone after a restart.
- **Connector settings sat beside security and durability settings** in one file, and every binding to
  the same Kafka cluster repeated its servers, its TLS options and its credential references.
- **Paths were set one by one, and an unset one meant "keep nothing".** A node started with no settings
  kept its registered queries only in memory; the journal, checkpoints, identity store and AI folder
  were five separate settings, and the AI layer (ADR-063) had already shown a better shape: one YAML
  file per object in a folder, read again while the node runs.

The owner's decisions: streams are declared in separate YAML files **whose file name is the stream's
name**, in a folder whose path is configurable; the same for lookups and sinks; the folders are
**watched** -- "as and when a new file is added a new stream should be created and if a file is taken
out then stream will be removed"; connector configuration goes in a folder of its own, never in
`application.yaml`; and a few root settings decide every path, each derived one still overridable.

## Decision

### 1. One file per object, named after it

| Folder | Setting | Default | One file holds what this holds inline |
|---|---|---|---|
| `streams/` | `pravaha.config.streams-dir` | `${pravaha.config.dir}/streams` | `pravaha.streams.<name>` -- schema, event-time, out-of-orderness, allowed-lateness -- plus an optional `source:` block, which is `pravaha.sources.<name>` |
| `lookups/` | `pravaha.config.lookups-dir` | `${pravaha.config.dir}/lookups` | `pravaha.lookups.<name>` |
| `sinks/` | `pravaha.config.sinks-dir` | `${pravaha.config.dir}/sinks` | `pravaha.sinks.<name>` |
| `connectors/` | `pravaha.config.connectors-dir` | `${pravaha.config.dir}/connectors` | a named connection: `plugin` and `options` (section 4) |

```yaml
# config/streams/txn.yaml -- declares the stream txn, and where its rows come from
schema: "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP"
event-time: event_time
out-of-orderness: 10s
source:
  plugin: filesystem
  options:
    path: data/incoming/txn.csv
```

- **The file name, without `.yaml` / `.yml`, is the name.** It must be one an inline block binds
  without brackets -- letters, digits, `_` and `-`, at most 128 -- or the file is refused
  (`PRV-1061`). Other files are ignored (logged at DEBUG), and so are hidden files and
  `*.yaml.example`.
- **The file is bound by Spring's own binder into the same classes the inline blocks bind into**, after
  Spring's own YAML flattening, so a file means exactly what the same block inline means -- a unitless
  `out-of-orderness: 60` is sixty seconds in both, and `${ENV}` / `${ENV:default}` resolve against the
  same environment. The rules are not forked: the stream's schema, event time and lateness go through
  the code the inline path uses, and its source's `schema` option must agree with the stream's
  (`PRV-1015`).
- **A file is stricter than a block**, where it can be: an unknown key is refused (`PRV-1064`, naming
  the key as written), where Spring ignores one inline; and a **literal secret is refused**
  (`PRV-1063`) -- any key whose last segment names a password, secret, token or API key, by the rule
  `NoSecretsInConfigurationTest` holds the repository to, must be `${ENV_NAME}`. The refusal names the
  setting and never the value, as ADR-063's provider files do.

### 2. Inline blocks keep working; one name, one declaration

Folder files and inline blocks are merged at start. **A name declared in a file and inline** --
`pravaha.streams` or `pravaha.sources` for a stream -- is refused at start (`PRV-1062`, naming both
places), and so is one name in `txn.yaml` and `txn.yml`. A folder found by convention may be missing
(it is empty); a folder named explicitly must exist (`PRV-1060`). An invalid file stops the node at
start, as invalid inline configuration does.

### 3. Watched by polling, every `pravaha.config.watch-interval`

The folders are looked at every `pravaha.config.watch-interval` -- **one setting for every folder,
default `10m`**; a unit is required (a bare number is `PRV-1023`), less than a second is `PRV-1026`,
and `0` or `off` reads the folders at start only. Each look takes the connections first, then streams,
lookups and sinks, so a connection and the binding that names it can arrive together.

- **A new file declares its object on the next look**: a stream goes into the catalogue with its source
  bound, a lookup is opened, a sink is bound, a connection becomes available. Logged and audited
  (`config.<kind>.declared`).
- **A file taken out removes its object on the next look.**
- **A changed file replaces it.** "Changed" is the file's settings, not its bytes: a comment or the
  order of keys is no change.
- **Except what a registered query uses.** A stream a query reads, a lookup it joins, a sink it writes,
  or a connection a binding names is **never changed or removed under it**. The running definition is
  kept; a `PRV-1065` warning names the file and the queries; the object is listed as *not applied*
  (`GET /api/v1/declarations`, the stream's `notApplied`, health detail, `pravaha status`, the console's
  Catalog). The change is tried again at every look, so it applies at the first look after the last of
  those queries is dropped -- or at a restart.
- **A file that cannot be used while the node runs never takes it down.** It is reported, coded and
  naming the file, and ignored until fixed; whatever it declared before keeps running. A folder that
  vanishes (an unmounted volume) is reported and removes nothing.
- Changes are made under the locks `POST /api/v1/streams` declares under -- the catalogue's, then the
  registry's -- and the question "does a query use it?" is asked under the registry's lock, so no
  registration falls between the question and the change. A name the API declared is refused to a new
  file (`PRV-1062`).

Every object says where it came from: `origin` is `file:<path>`, `config` (inline) or `api`, on
`GET /api/v1/streams`, `GET /api/v1/declarations` and `pravaha streams`.

### 4. Named connections

`config/connectors/<name>.yaml` holds a plugin and its options once:

```yaml
# config/connectors/kafka-prod.yaml
plugin: kafka
options:
  bootstrap.servers: broker1:9092
  ssl.truststore.password: ${KAFKA_TRUSTSTORE_PASSWORD}
```

A stream file's `source:`, a lookup file or a sink file -- and an inline block -- may write
`connector: kafka-prod` instead of, or as well as, `plugin:`. The plugin comes from the connection; the
effective options are the connection's with the binding's own laid over them, so **the binding wins on
a clash**. Naming a plugin that differs from the connection's is `PRV-1068`; naming a connection no file
declares is `PRV-1067` (at start a refusal, while running a report retried at each look). A connection
cannot name another. Changing or removing a connection a binding names is refused like any in-use
change, with the bindings named (`source:txn`, `lookup:users`, `sink:out`). `GET /api/v1/connectors`
lists each connection's plugin, file and users with every secret option shown as `****`; `pravaha
connectors list|show` reads it. The write path the console's editor will need is not built; the loader
and validator (`DeclarationFile.read`) are the ones a write API would call before replacing a file
atomically.

### 5. Three roots, everything else by convention

| Root | Default | Under `PRAVAHA_HOME` | Beneath it, by convention |
|---|---|---|---|
| `pravaha.data.dir` | `data` | `$PRAVAHA_HOME/data` | `registry.journal`, `checkpoints/`, `identity/identity.journal` (when identity is on); the catalogue and alerts journals stay where they always were, beside the registry journal |
| `pravaha.config.dir` | `config` | `$PRAVAHA_HOME/conf` | `streams/`, `lookups/`, `sinks/`, `connectors/`, `ai/` |
| `pravaha.external-lib.dir` | `external_lib` | `$PRAVAHA_HOME/external_lib` | `java/*.jar` on the server's classpath, `python/` on the console's and the CLI's `sys.path` |

Relative paths are relative to the working directory. **Each derived path is still a setting** -- in
YAML, as `--pravaha.<key>=...` or as an environment variable -- and an explicit value wins; `none` on a
derived path switches that one store off. The conventions are resolved in one place, `NodeLayout`, for
the server (before anything binds) and the embedded engine. The data root is created at start, and one
that cannot be created or written stops the node (`PRV-1066`). One `layout:` line at start names every
store and folder, and a second line names the jars `external_lib/java` put on the classpath.

**Persistence is the default.** A node started with nothing set keeps its registry journal and
checkpoints in `./data`, so a restart brings back its queries, their views and their source positions.
It used to forget them. `pravaha.data.dir: none` is the old in-memory node, on purpose, and is the only
case that still warns at start. The catalogue and alerts journals are deliberately not re-derived: a
node that names its registry journal elsewhere keeps its grants and alert decisions where they were.

### 6. `external_lib`

Every top-level `*.jar` in `<external-lib>/java/`, sorted, is put on the classpath by
`bin/pravaha-server` -- the Spring Boot launcher's `-Dloader.path`, as `$PRAVAHA_HOME/plugins` always
was -- so a connector jar dropped there is found by `ServiceLoader` like a shipped one. The classpath is
fixed before the JVM starts, so the launcher takes the root from `--pravaha.external-lib.dir=` on its
command line, `PRAVAHA_EXTERNAL_LIB`, or the home; a value only in `application.yaml` cannot move it.
It is read **at start only**: a jar added later needs a restart, and a node started without the
launcher says which jars it found and did not load. The embedded engine uses its application's own
classpath. `<external-lib>/python/` is added to `sys.path` by the console and the `pravaha` CLI at
start. Code in either folder runs with that process's privileges.

## Alternatives rejected

- **`pravaha.streams.dir`.** `pravaha.streams` is the map of inline streams, so `dir` under it is read
  as a stream named "dir" whose body is a string, and no stream could ever be called `dir`. The folders
  get their own block, `pravaha.config.*`, beside their root.
- **A `WatchService`.** The owner asked for periodic polling. A poll also behaves the same on a
  network or container-mounted volume, where file events are unreliable, and ten minutes is the pace a
  configuration change is made at.
- **Applying the "safe" part of an in-use change** -- a sink's non-structural option, a lookup's cache
  size. Deciding what is safe per plugin is a judgement each plugin would have to make and every one
  would get wrong somewhere; a query's state was accumulated from what was running. Every in-use change
  is refused, said plainly, and applied once nothing holds the object.
- **Removing an in-use object anyway and failing its queries.** A file taken out by mistake would stop
  production queries; keeping it and saying so costs one warning.
- **Per-folder intervals.** One interval is one thing to know; nothing measured needs more.
- **Keeping memory-only as the default.** Every guide's first restart lost the reader's queries; a
  deployment that wants nothing kept writes `none` and is told at start.

## Consequences

- A node is described by a folder tree, and a stream can be added or removed without touching the
  node's configuration or restarting it, unless something uses it.
- `pravaha init` scaffolds `conf/streams/txn.yaml` (the project is a home, mounted at `/opt/pravaha`) and `external_lib/`; the image, compose stack and chart mount
  `conf/streams`, `conf/lookups`, `conf/sinks` and `conf/connectors` and `external_lib/`.
- **Upgrading:** a node that set no journal now writes `./data` (or `$PRAVAHA_HOME/data`) and recovers
  its queries on restart. Set `pravaha.data.dir: none` to keep the old behaviour. The server's own test
  classpath sets `none`, so tests do not write into the source tree.
- New codes: `PRV-1060` (folder missing), `PRV-1061` (file name), `PRV-1062` (declared twice),
  `PRV-1063` (literal secret), `PRV-1064` (invalid file), `PRV-1065` (in use, not applied), `PRV-1066`
  (data root unusable), `PRV-1067` (unknown connection), `PRV-1068` (plugin clash).

## Addendum (2026-10-12): the source is given the stream's schema; `PRAVAHA_CONFIG_DIR` is the CLI's no longer

- **STREAMFILESCHEMA-1.** Section 1 says to write the schema once, at the top of a stream file, and
  leave the source's option out -- and a plugin that requires `schema` in its options (filesystem,
  Kafka) then refused the first registration with `PRV-5091 … PRV-5001 … requires 'schema'`. A
  stream's source that sets no `schema` option is now given the stream's, the way it is already given
  its `event-time`; this holds for a stream file and for `pravaha.streams` with `pravaha.sources`
  inline alike. A source that sets its own still must agree with the stream's (`PRV-1015`).
- **ENVCONFIGDIR-1.** The command line kept its token, contexts and assistant configuration in
  `$PRAVAHA_CONFIG_DIR`, and Spring's relaxed binding reads that variable as `pravaha.config.dir`: a
  shell that exported it for the command line and then started a server read every folder above from
  the command line's directory and declared nothing. The command line's variable is now
  `PRAVAHA_CLI_DIR` (the old name is still read, after the new one, with one warning on stderr), and a
  server whose `pravaha.config.dir` comes from `PRAVAHA_CONFIG_DIR` alone -- no command-line argument or
  system property sets it, and the YAML does not set it to the same path -- refuses to start with
  `PRV-1069`, naming the variable and the fix.
