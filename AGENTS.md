# AGENTS.md

Facts for AI agents working on jpm. The reasons behind them are in [CONTRIBUTING.md](CONTRIBUTING.md) and
[docs/adr](docs/adr); what to work on next is in [ROADMAP.md](ROADMAP.md).

## Commands

| What | Command |
|---|---|
| First build | `./bootstrap.sh` |
| Build | `bin/jpm build` |
| All local checks (run before saying a change is done) | `bin/check` |
| Unit and end-to-end tests | `bin/jpm test --exclude-tag live` |
| One test class | `bin/jpm test dev.jpm.build.BuildTest` |
| One test method | `bin/jpm test 'dev.jpm.e2e.EndToEndTest#workspace(java.nio.file.Path)'` |
| Rewrite end-to-end transcripts | `JPM_UPDATE_EXPECTED=1 bin/jpm test dev.jpm.e2e.EndToEndTest` |
| Ratchets | `java checks/Ratchets.java` (`--list <name>` shows the hits, `--update` lowers baselines) |
| Comments in a file | `java checks/Comments.java list <path>` |
| Native binary | `bin/jpm build --native` (`cli/dist/jpm`) |

If `java` on PATH is jpm's shim and `bin/jpm` fails, set `JAVA_HOME` to a JDK 21.

## Where things live

- `api/`: the public API module (`dev.jpm.api`, `module-info.java` exports only it). `cli/`: jpm itself.
- `cli/src/dev/jpm/cli`: one class per command (`*Command`), the registry `Commands` (the source of `jpm help`, the README table and completions), `Main`, shims, upgrade.
- `cli/src/dev/jpm/resolve`: dependency resolution and `project.lock`. `cli/src/dev/jpm/maven`: repositories, POMs.
- `cli/src/dev/jpm/build`: compiling, jars, bundles, patches, scripts, the test runner.
- `cli/src/dev/jpm/toolchain`: finding and installing JDKs. `cli/src/dev/jpm/importer`: Maven and Gradle import.
- `cli/src/dev/jpm/log`: all console output. `cli/src/dev/jpm/util`: files, processes, HTTP, `JpmException`.
- `cli/src/assets/dev/jpm/project.schema.json`: the manifest schema; `schema/examples` must stay valid/invalid.
- `cli/test/dev/jpm/<area>`: unit tests. `cli/test/dev/jpm/testing`: shared helpers (`JpmHome`, `TestRepo`).
- `cli/test/dev/jpm/e2e` with `fixtures/` and `fixtures/expected/`: end-to-end tests of the binary.
- `docs/adr`: decisions. `checks/`: ratchets, coverage, the comment tool.

## Rules that must not be broken

- No dependencies in jpm: the JDK only.
- No `//` or `/* */` comments in `api/` or `cli/` sources and tests. Put decisions in `docs/adr`; Javadoc is fine on API.
- No `System.out`/`System.err` outside `dev.jpm.log.Log`.
- jpm never parses `.java`/`.kt` sources itself.
- Tests never use the network unless tagged `@Tag("live")`, and never touch `~/.jpm`.
- No implementation class (`dev.jpm.<area>.impl`) is used from another area.
- Ratchet baselines in `checks/ratchets.properties` never go up.
- An end-to-end transcript in `fixtures/expected/` changes only for an intended behavior change, which the
  commit message names.
- Do not push, tag, or file issues without being asked. Work on a local branch and merge it into `main`.

## Done means

1. `bin/check` passes.
2. Behavior changes have tests: a unit test, and an end-to-end scenario for anything a user sees.
3. User-facing changes are in `README.md` (commands, manifest fields, environment variables), and in
   `cli/src/assets/dev/jpm/project.schema.json` for manifest fields.
4. The roadmap item is ticked in `ROADMAP.md` if the change finishes it.
5. The commit message says what changed and why, and ends with the attribution line if one is asked for.
