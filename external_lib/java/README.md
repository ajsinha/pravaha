# external_lib/java — jars for the engine's classpath

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../LICENSE`](../../LICENSE).

Drop a jar here -- a connector of your own, a JDBC driver for a database other than PostgreSQL, a
library a plugin needs -- and it is on the engine's classpath **at the next start**
([ADR-064](../../docs/design/adr/064-configuration-folders-and-node-layout.md)).

- **What is read:** every `*.jar` directly in this folder, sorted by name. Sub-folders and other files
  (this README) are not.
- **How:** `bin/pravaha-server` puts each jar on Spring Boot's `-Dloader.path`, so a plugin in one is
  found by `ServiceLoader` like a shipped one, and is bound by name like any other
  ([CONNECTOR_DEVELOPMENT.md](../../docs/development/guides/CONNECTOR_DEVELOPMENT.md), §9).
- **At start only.** A JVM cannot safely swap jars while it runs: a jar added, replaced or removed
  needs a restart. The node's start-up log lists the jars it loaded, and any it found here and did not
  load -- a node started from an IDE or with `java -jar`, not through the launcher, loads none.
- **Where:** this folder from a source checkout, `$PRAVAHA_HOME/external_lib/java/` under a home
  (`/opt/pravaha/external_lib/java` in the image). Move the root with `--pravaha.external-lib.dir=...`
  on the `bin/pravaha-server` command line or `PRAVAHA_EXTERNAL_LIB` -- not in `application.yaml`,
  because the classpath is fixed before the JVM starts.
- **Licences:** check what you add. A driver under the GPL, or its vendor's terms, is why it is not in
  the server jar.

> **Trust.** Code here runs inside the engine with the engine's privileges: it can read every row,
> every view, the identity store and every credential the process holds. Put nothing here you would not
> run as that user, and keep the folder writable only by whoever deploys the node.

Only this README is tracked; the jars you add are git-ignored.
