# external_lib/python — modules for the console and the CLI

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../LICENSE`](../../LICENSE).

A Python module or package here is importable by the console and by the `pravaha` CLI without being
installed ([ADR-064](../../docs/design/adr/064-configuration-folders-and-node-layout.md)): each puts this
folder first on `sys.path` when it starts.

- **The console** finds the root in `paths.external_lib` (its `config/application.yaml`), then
  `PRAVAHA_EXTERNAL_LIB`, then `$PRAVAHA_HOME/external_lib`, then the checkout's `external_lib/` beside
  `pravaha-console/`, and logs the folder it added
  ([CONSOLE_DEVELOPMENT.md](../../docs/development/guides/CONSOLE_DEVELOPMENT.md)).
- **The CLI** uses `PRAVAHA_EXTERNAL_LIB`, then `$PRAVAHA_HOME/external_lib`, then `./external_lib` in the
  directory it runs from.
- **At start.** A module changed while the console runs is picked up at its next start.
- A missing folder is fine.

> **Trust.** Code here runs inside the console, or the CLI, with its privileges: in the console it can
> reach every session the console holds and act as any signed-in person. Put nothing here you would not
> run as that user, and keep the folder writable only by whoever deploys the console.

Only this README is tracked; what you add is git-ignored.
