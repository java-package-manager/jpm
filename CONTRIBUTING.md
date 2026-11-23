# Contributing to jpm

How to build, test and release jpm, and the rules its code follows. [AGENTS.md](AGENTS.md) has the same
facts in a form for AI agents; [ROADMAP.md](ROADMAP.md) says what comes next.

## Build and run

jpm builds itself. It needs a JDK 21; `bootstrap.sh` compiles it once with plain `javac`.

```sh
./bootstrap.sh                 # once: javac, then jpm builds itself into dist/
bin/jpm build                  # cli/dist/jpm-<version>.jar and api/dist/api-<version>.jar (bin/jpm runs
                               # them; bin\jpm.cmd on Windows)
bin/jpm build --native         # cli/dist/jpm, the native binary (GraalVM, installed if needed)
```

The repository is a workspace of two modules: `api` (`dev.jpm.api`, the public API, which tools use as
published as `dev.jpm:jpm-api` to `https://repo.thenextlvl.net/releases` with every release) and `cli` (jpm itself, which exports nothing). Each has a `module-info.java`.

To try a change on another project, run `bin/jpm` (or `cli/dist/jpm`) there. To keep your own `~/.jpm`
out of it, give it an empty home: `JPM_HOME=$(mktemp -d) bin/jpm build`.

If `bin/jpm` fails with `Could not find option 'UseSerialGC'`, the `java` on your PATH is the shim of a
jpm older than 0.8.1. Set `JAVA_HOME` to a JDK, or upgrade that jpm.

## Test

```sh
bin/check                       # everything CI runs that can run here: build, tests, jpm check, schema, ratchets
bin/jpm format                  # format the sources with jpm-format, which `jpm check` checks
bin/jpm test --exclude-tag live # unit and end-to-end tests, no network
bin/jpm test --tag live         # the few tests that need Maven Central
bin/jpm test dev.jpm.e2e.EndToEndTest
checks/coverage.sh              # tests with JaCoCo; report in .cache/coverage/
checks/perf.sh cli/dist/jpm     # no-op install, build and info against checks/perf-budget.properties
```

- Unit tests live next to the area they test (`cli/test/dev/jpm/<area>`, `api/test/...`). Every test gets an empty jpm home
  (`dev.jpm.testing.JpmHome`), so tests never touch `~/.jpm`. Local Maven repositories come from
  `dev.jpm.testing.TestRepo`.
- Tests need no network. One that does is tagged `@Tag("live")` and runs in its own CI job; the
  `live-tests` ratchet counts them.
- The end-to-end tests (`dev.jpm.e2e`) run the jpm binary on `fixtures/` and compare everything it prints
  with `fixtures/expected/` (see [fixtures/README.md](fixtures/README.md)). A changed transcript is a
  change in behavior: rewrite it with `JPM_UPDATE_EXPECTED=1`, review the diff, and say why in the commit
  message.

## Rules for the code

- **No dependencies.** jpm uses the JDK only.
- **No comments.** `//` and `/* */` comments are not allowed (`checks/Ratchets.java` counts them). A
  non-obvious decision goes into an ADR in [docs/adr](docs/adr); `/delete-comment` cleans up comments that
  crept in. Javadoc is not a comment: it is required on public and internal API, unwanted on
  implementations.
- **Layers.** `dev.jpm.api…` (the `api` module) is the public API: exported, with full Javadoc (javac
  fails the build on anything missing), kept stable once jpm reaches 1.0. `dev.jpm.<area>` (such as `resolve`, `build`, `toolchain`) is internal API: the interfaces and
  records areas use to talk to each other, with Javadoc, not exported. `dev.jpm.<area>.impl` holds
  implementations, without Javadoc, and is never used from another area. `checks/Ratchets.java` checks
  both, the second over the compiled classes (jdeps).
- **No reading of Java sources.** jpm does not parse `.java` or `.kt` files itself; what it needs comes
  from the compiler, from compiled classes or from the manifest.
- **Output goes through `dev.jpm.log.Log`.** No `System.out` or `System.err` anywhere else (a ratchet
  checks it). Command results go to stdout with `Log.out`; everything else goes to stderr.
- **Errors are `JpmException`s** that say what went wrong, where (`JpmException.at(file, line, ...)`)
  and what to do next (`withHint`). Stack traces only with `--verbose`.
- **Ratchets only go down.** `checks/ratchets.properties` holds the baselines; when a count falls,
  `java checks/Ratchets.java --update` lowers it in the same change.
- **No stability before 1.0.** The CLI, `project.jsonc`, `project.lock` and the API may change in any
  release. The old form is removed in the same change, with no deprecation period and no converter.

## Workflow

- One local branch per roadmap item, or part of one. Before it is merged into `main`, `bin/check` passes.
- Merge into the local `main` and delete the branch. `main` is pushed once, after all branches are
  merged.
- Some checks only run in CI: other operating systems, the native binary, live tests, coverage. If one
  fails after a push, fix it on `main` before starting new work.
- A roadmap item is ticked in the commit that finishes it. Behavior changes only where the roadmap says
  so.

## Releases

- The version is `"version"` in `project.jsonc`. When `main` has a version with no tag yet, CI tags that
  commit (the plain version, no `v` prefix) and releases it: native binaries for Linux (x64, arm64), macOS
  (arm64, x64) and Windows (x64), `SHA256SUMS`, the install scripts and `project.schema.json`. Nothing is
  published unless every platform passes, and a version is never released twice.
- A version with a `-` (such as `1.0.0-rc1`, or `0.13.0-beta.0` from jpm-changeset's prerelease mode) is a
  GitHub prerelease, which `jpm upgrade` and the install scripts skip. The `canary` release holds a `VERSION`
  file naming the newest release or prerelease, which `jpm upgrade --canary` and `JPM_VERSION=canary` install.
- Every push and pull request, the Version packages one too, gets the full suite: every platform, live
  tests, Java versions and coverage, so a release is known to pass before it is merged. Only a release
  publishes (the binaries, the GitHub release and `dev.jpm:jpm-api`); there are no builds of other commits.
- A change users notice comes with a changeset: `jpm changeset add --module jpm --patch -m "..."` (or
  `--minor`, `--major`) writes one to `.changeset/`; commit it with the change. Pull requests are checked
  for one (`jpm changeset status --check`).
- The Release workflow keeps a "Version packages" pull request with what `jpm changeset version` makes of
  the pending changesets: the new `"version"` and `cli/CHANGELOG.md`. Merging it is the release; the
  version's changelog entry becomes the GitHub release's notes.
- The SPDX license list workflow checks spdx.org weekly and opens an "SPDX license list <version>" pull request
  (with a changeset) when there is a new list.
- So releasing is merging that pull request (or any commit that changes `"version"`); nothing is tagged by
  hand.

## CI

`.github/workflows/ci.yml` runs on every pull request and every push to `main`:

- **version** decides whether the run releases (`main` with an untagged version).
- **build**, on Linux (x64, arm64), macOS (arm64, x64) and Windows (x64): `bin/check`, the native binary, the
  end-to-end tests on the native binary, smoke tests of the binary (a project, toolchains, native builds,
  imports of real Maven and Gradle builds, Git dependencies, bundles) and the install scripts against this
  build; on linux-x64 also the performance budget (`checks/perf.sh`). The binaries are uploaded as artifacts.
- **live tests** (`--tag live`, against Maven Central), **java versions** (every target with every supported
  JDK, see [docs/java-versions.md](docs/java-versions.md)) and **coverage** (the report is an artifact).
- **changesets**, on pull requests: a changed module needs a changeset.
- **publish api** and **release**, only when the run releases.

`release.yml` keeps the Version packages pull request, and `spdx.yml` the weekly SPDX license list update.
