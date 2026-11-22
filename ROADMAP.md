# Roadmap

What comes next for jpm and the projects around it (jpm-intellij, jpm-publish, extensions).
Items are grouped into phases; a phase can start before the previous one is finished, but the order
matters where one item depends on another (noted as **Needs:**).

Status: `[ ]` open, `[~]` in progress, `[x]` done.

---

## Phase 0: Decisions to make first

These shape everything below, so they are settled before the restructure starts.

- [x] **Shape of the public API.** Decided: a versioned protocol with a client jar; see the plan below. jpm ships as a native binary, which other JVM programs cannot load.
      Choose one:
  - a library jar (`dev.jpm:jpm-api` on Maven Central) that reads `project.jsonc` and `project.lock` and
    runs resolution in-process, used by jpm itself, jpm-intellij and extensions; or
  - a versioned machine protocol (for example JSON over stdin/stdout, like LSP/BSP) with a small client
    library on top.

  The library is simpler for consumers; the protocol keeps one implementation and works from any
  language. Whichever is chosen, `jpm info --json/--ide/--classpath` and `jpm schema` go away (Phase 4).
- [x] **API stability policy.** Decided: see the plan below. What semver means for the public API, how long deprecated parts stay,
      and which Java version the API jar targets (it must load in IntelliJ's runtime and in extensions).
- [x] **Layering rules.** Accepted as proposed:
  - `dev.jpm.api…`: public API, exported, full Javadoc, stable.
  - `dev.jpm.<area>` (for example `resolve`, `build`, `toolchain`): internal API, the interfaces and
    records that areas use to talk to each other, with Javadoc, not exported.
  - `dev.jpm.<area>.impl`: implementations, no Javadoc, never referenced across areas.

  A `module-info.java` that exports only `dev.jpm.api` enforces this at compile time.
- [x] **Java support matrix.** Decided: projects jpm builds; see the plan below. "Still officially supported" means every Java version that has public
      updates from at least one major vendor (today 8, 11, 17, 21, 25 and the current feature release).
      Decide whether this is a target for *projects jpm builds*, for *the API jar*, or both.
- [x] **Comment policy.** Comments (`//`, `/* */`) are removed and banned by a lint rule; see Phase 1.
      Javadoc is not a comment: it is required on public and internal API and unwanted on
      implementations.

---

## Phase 1: Foundations

Small, independent items that make the rest of the work easier.

### Logger

- [x] Replace `dev.jpm.util.Log` with a proper logger: levels (`error`, `warn`, `info`, `debug`,
      `trace`), one place that decides on color, and no `System.out`/`System.err` calls elsewhere
      (there are about 40 today).
- [x] Colors:
  - errors are always red;
  - warnings are orange (256-color `38;5;208`, yellow as fallback on 16-color terminals);
  - debug messages are yellow.
- [x] `--color=auto|always|never`, respecting `NO_COLOR`, `FORCE_COLOR` and `JPM_COLOR`.
- [x] `--quiet` and `--verbose` flags next to `JPM_VERBOSE`.
- [x] Progress output (downloads, compilation) goes through the logger, so it is suppressed when
      output is not a terminal.

### Contributor docs (all jpm projects)

- [x] `CONTRIBUTING.md` in every repository: how to build, test, run against a local checkout,
      release, and the code style and layering rules from Phase 0.
- [x] `AGENTS.md` in every repository: the same facts in a form for AI agents: commands to build and
      test, where things live, rules that must not be broken (layering, no source parsing, no
      dependencies in jpm), and what "done" means for a change.
- [x] Keep both short and link to each other instead of repeating content.

### Comments (all jpm projects)

- [x] Remove comments across the codebases, following the task below. Comments are `//` and `/* */`;
      Javadoc (`/** */`) is not in scope here. Javadoc on implementations, which is most of it today,
      is removed by the layering work in Phase 2.

```text
Take a look at every code comment in <my codebase | this section of the code>.

Categorise each comment as:

- Tautology: simply repeats what is already visible from the code
- Excuse: many comments allow the agent to get away with a hacky implementation, which then gets propagated through the code
- Stale: references code or implementation information that is no longer relevant
- Decision Record: documents an important, non-obvious decision about the code

For each comment, take this action:

- Tautology/Stale: delete the comment
- Excuse: delete the comment and file an issue to fix the hacky implementation
- Decision Record: move it into an ADR

Once this is done:

- Create a lint rule banning comments for <my codebase | this section of the code>
- Take a ratchet approach, assuming not all comments will be deleted this PR
- Create a /delete-comment skill in this repository so we can repeat this process later
- Estimate for me how many lines of code we'll shed by deleting all comments
```

---

## Phase 2: Restructure jpm

The goal is code that a new contributor can read top to bottom.

### Split the large classes

- [x] `cli/Commands.java` (≈1,300 lines): one class per command, plus a command registry that is the
      single source for `jpm help`, the README command table and shell completions.
- [ ] `importer/GradleImport.java` (≈900 lines), `resolve/Resolver.java` (≈670 lines) and
      `Manifest.java` (≈650 lines): split by responsibility (parsing, validation, inheritance, model).

### Remove duplication and unnecessary abstractions

- [ ] Audit every class and interface with a single implementation or a single caller and inline it
      where it adds nothing.
- [ ] One JSON/JSONC reader and writer, shared by jpm, jpm-publish and jpm-intellij (jpm-publish has
      its own `Json.java` today).
- [ ] One HTTP stack (`util/Http.java` and `util/Net.java` overlap).
- [ ] One place for version parsing and comparison (Maven, Gradle, npm syntaxes) with one entry point.
- [x] The help text in `cli/Main.java` and the README table are generated from the command registry
      instead of being written twice.

### Public API, internal API, implementations

**Needs:** layering rules from Phase 0.

- [ ] Move code into the three layers.
- [ ] Javadoc on every public and internal API type and member; none on implementations.
- [x] Build fails on missing Javadoc in `dev.jpm.api` and on exported packages outside it.

### Tests

- [ ] Reorganize tests to mirror the new package layout: unit tests per area, plus one set of
      end-to-end tests that run the `jpm` binary on the projects in `fixtures/`.
- [ ] Remove tests that only check implementation details, repeat other tests, or exist for a bug in
      code that no longer exists. Merge grab-bag tests (for example `SmallGapsTest`) into the tests of
      the feature they cover.
- [x] Make tests hermetic: serve Maven and Git fixtures from a local server or directory instead of
      the network (about 10 test classes reach out today). Keep a small, separately run set of live
      tests against Maven Central.
- [ ] Shared test helpers (temporary projects, fake repositories) in one place.
- [x] Coverage report in CI, to find dead code during the restructure.

---

## Phase 3: Compiler agnostic, every supported Java version

### No manual reading of Java source

jpm must not parse `.java` (or `.kt`) files itself; whatever it needs comes from the compiler, from
compiled classes or from the manifest.

- [ ] `build/Modules.java` reads `module-info.java` with regular expressions. Read the compiled
      `module-info.class` instead, or let the compiler resolve modules (`--module-source-path`).
- [ ] `build/Scripts.java` reads `//DEPS` lines from script sources. Move script dependencies into the
      manifest (`scripts` entries with their own dependencies) and drop `//DEPS`, or keep it only as an
      import format.
- [ ] `build/Compiler.java` matches javac's error text (`"not allowed with --release"`). Decide such
      cases from the arguments before compiling instead.
- [ ] Generated sources (`Defines`, the bundle launcher) are generated per language or as class files,
      so a Kotlin-only module does not need Java source generation.
- [ ] A check in CI that fails when `src/` gains new code that opens `.java` files for reading.

### Every supported Java version

**Needs:** Java support matrix from Phase 0.

- [ ] Build projects targeting each supported version (`"target": "8"` through the newest), including
      the differences around `--release`, modules (none on 8), and `-source/-target` on Java 8.
- [ ] Test matrix in CI: build and test the fixtures with each supported JDK as `"java"` and each
      supported `"target"`.
- [ ] Clear error when a combination cannot work (for example a module project targeting 8).
- [ ] Follow vendor end-of-life dates: dropping a version is an announced, deliberate change.

---

## Phase 4: Public API replaces machine-readable commands

**Needs:** API shape (Phase 0), layering (Phase 2).

- [ ] Public API covering what `jpm info --json/--ide/--classpath` and `jpm schema` give today:
      project model, modules, folders, scripts, dependencies with scopes and sources, every classpath,
      resolved defines, and the manifest schema.
- [ ] Publish the API to Maven Central with Javadoc and sources.
- [ ] **jpm-intellij:** use the public API instead of running `jpm info --ide` and parsing its output.
- [ ] **jpm-publish:** use the public API instead of `jpm info --json`.
- [ ] Extensions get a documented way to use the API (for example a template project).
- [ ] Remove `jpm info --json/--ide/--classpath` and `jpm schema` in the release that ships the API,
      with no deprecation period. Plain `jpm info` stays as a human-readable summary.

### Project-level extensions

Extensions are only installed globally today (`jpm add -g`), so CI has to install each one before it
can build, and the IDE toolbar only knows the extensions written into the plugin.

- [ ] Declare extensions in the root `project.jsonc`, with a version, next to their settings in the
      existing `"extensions"` field. Pin them in `project.lock`.
- [ ] `jpm add` without `-g` adds an extension to the root `project.jsonc` (and `jpm remove` takes it
      out); `-g` keeps installing it globally. jpm detects extensions automatically from a marker
      the extension declares in its own manifest, so no extra flag is needed.
- [ ] `jpm install` installs the declared extensions, so CI needs no `jpm add -g` steps.
- [ ] Settings for an extension that is not declared in the root `project.jsonc` give a warning that
      names the extension and the command that fixes it (`jpm add <extension>`).
- [ ] A declared extension takes precedence over a global one of the same name inside the project.
- [ ] Every extension exposes a small public API that lists its commands with a description (and
      their options), so IDEs can show them without knowing the extension in advance, and the checks it
      adds to `jpm check`.
- [ ] **jpm-intellij:** the jpm toolbar lists the commands of every installed and declared extension
      from that API instead of a built-in list.

### jpm check

One command for CI, like `./gradlew check`: every check jpm and the project's extensions know, then
the tests.

**Needs:** the extension API above.

- [ ] `jpm check` runs jpm's own project checks first (manifest, paths that do not exist, license,
      unused dependencies, duplicate classes), then the checks of the extensions declared in the project
      (for example `changeset status`, `format --check`), then the tests.
- [ ] It runs every check even when one fails and reports all problems before it exits, so failing
      tests never hide other problems.
- [ ] A check reports problems with a severity (error or warning), a message and a location (file and
      line where there is one), so CI, jpm-intellij and the language server can all show them.
- [ ] Only extensions declared in the project add checks, so CI and every developer run the same ones.
- [ ] `--no-test` for the quick checks only; `--fail-on-warning` for CI.
- [ ] A machine-readable report (SARIF or JUnit XML), so GitHub shows problems as annotations.
- [ ] Output grouped by check, using the logger's colors.

---

## Phase 5: Ecosystem

### jpm-changeset

An extension that works like [changesets](https://github.com/changesets/changesets) for npm.
`.changeset/` is already reserved by jpm.

- [ ] `jpm changeset` (or `jpm changeset add`): ask which workspace modules changed, the bump
      (major/minor/patch) and a summary; write a Markdown file to `.changeset/`.
- [ ] `jpm changeset version`: apply pending changesets: bump `version` in each `project.jsonc`, bump
      dependents inside the workspace, write `CHANGELOG.md` per module, delete the applied files.
- [ ] `jpm changeset status`: list pending changesets. It is also a check in `jpm check`, which fails
      when a changed module has no changeset.
- [ ] `jpm changeset publish`: publish bumped modules through jpm-publish and tag them.
- [ ] Prerelease mode (`pre enter/exit`), and linked/fixed version groups.
- [ ] A GitHub Action that opens and updates a "Version packages" pull request.

### jpm-format

- [ ] Get jpm-format into a usable state:
  - [ ] formatting with parity to IntelliJ IDEA's formatter, so formatting in the IDE and with
        `jpm format` gives the same result;
  - [ ] linting rules with parity to IntelliJ IDEA's inspections;
  - [ ] a language server (LSP) for formatting and lint diagnostics in VS Code and other editors.
- [ ] jpm-format adds `format --check` to `jpm check`, and all jpm projects run `jpm check` in CI.

### jpm-intellij: migration UI

Migrating a project today only shows the output of `jpm import` in a plain text box.

- [ ] Show the generated `project.jsonc` in an editor with syntax highlighting, and let the user edit
      it before it is written.
- [ ] Show warnings prominently, above or next to the manifest rather than at the bottom of the
      output, so they are reviewed before migrating.
- [ ] Offer to switch from the Maven layout to the jpm layout: move the source, resource and test
      folders, and drop `"layout": "maven"` from the manifest.
- [ ] Offer to delete what is left of the old build: Gradle files and folders (`build.gradle(.kts)`,
      `settings.gradle(.kts)`, `gradle/`, `gradlew`, `gradlew.bat`, `gradle.properties`), and `pom.xml`
      and the Maven wrapper for Maven projects.
- [ ] Scan the whole project and flag everything that still uses Gradle or Maven, for example CI
      workflows, Dockerfiles, scripts that call `./gradlew` or `mvn`, Renovate or Dependabot settings,
      `.gitignore` entries and IDE run configurations.

### Editing project.jsonc

- [ ] **jpm-intellij:** ctrl+click on values in `project.jsonc` goes to what they name:
  - `main`, `defineClass` and `scripts`: the class (or the script file);
  - dependencies and `exclude`: the library under External Libraries in the project view;
  - `relocate`: the package in the project view;
  - `license`: the license file (`LICENSE`, `LICENSE.md`, `LICENSE.txt`, `LICENCE`, `COPYING` and the
    other common spellings);
  - `filter`: the file;
  - `workspace`, `sources` and `resources`: the folder in the project view;
  - `patches`: the patch file;
  - `hooks`: the script the hook runs;
  - `java`: the JDK;
  - values in `define` for a dependency: the define where that dependency declares it.
- [ ] Every value that names a file or folder (`filter`, `patches`, `scripts`, `sources`, `resources`,
      workspace modules, env files) gives a warning when it does not exist. One shared check, not one
      per field.
- [ ] **jpm-intellij:** renames update the manifest: renaming a class updates `main` and
      `defineClass`, moving a package updates `relocate`, renaming a script updates the `hooks` that run
      it, moving a file or folder updates the paths that name it.
- [ ] Completion of dependency coordinates and versions, and the resolved version from `project.lock`
      shown next to each dependency (inlay hint).
- [ ] An inspection for dependencies with a newer version, with a quick fix that runs `jpm update`.
- [ ] Documentation from the schema on hover, for every field.
- [ ] jpm-intellij and the language server (see jpm-format) highlight jpm's warnings and errors in
      `project.jsonc`, at the value they are about.

### jpm-intellij: more

- [ ] Run configurations generated from `main` entries and `scripts`.
- [ ] A dependency graph view, using what `jpm why` knows.
- [ ] New projects ignore jpm's folders (`dist/`, `.cache/`, `.env.local`) in Git. The wizard runs
      `jpm init`, which only writes `.gitignore` when there is none, so a `.gitignore` that exists
      already (for example one IntelliJ created) is left without them. `jpm init` and `jpm import`
      add the missing entries to an existing `.gitignore` instead of skipping it.

### Licenses

- [ ] The schema offers every SPDX license id as a value for `license`, generated from the SPDX
      license list (https://spdx.org/licenses/, machine-readable at
      https://spdx.org/licenses/licenses.json) and linking to it. It still accepts SPDX expressions such
      as `MIT OR Apache-2.0`.
- [ ] `jpm check`: when the project has a license file but no `license`, suggest the SPDX id, detected
      from the file's text where possible; warn when `license` and the license file do not match.
- [ ] `jpm import` fills in `license` the same way.
- [ ] Creating a license: when `license` is set and no license file exists, create one from the SPDX
      text (with the year and the author filled in). `jpm init` asks for (or takes) a license and does
      the same.

### Renovate

- [ ] A Renovate manager for `project.jsonc` (Maven coordinates, `github:` dependencies, Gradle
      plugins) that keeps comments and formatting, and updates `project.lock` with
      `jpm install`. Start with a shareable preset using Renovate's regex manager, then contribute a
      native manager upstream.
- [ ] Renovate updates the `"java"` field and the version pins in the install scripts.
- [ ] Use it on the jpm repositories themselves.

### More

- [ ] Dependabot support, or document that Renovate is the supported option.

---

## Phase 6: User-facing documentation

- [ ] Rewrite the README for users, not contributors: what jpm is, install, a five-minute start, the
      everyday commands, and links to the rest. The README is about 600 lines today and mixes release
      engineering, internals and reference.
- [ ] Move reference material (manifest fields, version syntax, environment variables, how resolution
      works) into `docs/`, generated from the schema and command registry where possible.
- [ ] Move release and CI details into `CONTRIBUTING.md`.
- [ ] Keep `MIGRATION.md`, linked from the README.
- [ ] A docs site built from `docs/` (optional, once the content has settled).

---

## Suggested additions

Items that are not in the original list but would pay off.

- [x] **Shell completions** for bash, zsh, fish and PowerShell, generated from the command registry
      (`jpm completions <shell>`).
- [ ] **`jpm doctor`:** check the machine, not the project: PATH order, shims, installed JDKs, cache
      health and network access to configured repositories, and print what to fix. Project checks
      belong in `jpm check`, so a CI result never depends on the runner.
- [ ] **More project checks for `jpm check`:** unused dependencies and duplicate classes on the
      classpath, both read from compiled classes, not sources.
- [ ] **Lockfile merge conflicts:** `jpm install` resolves a `project.lock` with conflict markers by
      resolving again, instead of failing.
- [ ] **Private repositories:** credentials for downloading from Maven repositories that need them,
      from environment variables like jpm-publish's (`JPM_REPO_<NAME>_USERNAME` / `_PASSWORD`), never
      from `project.jsonc`.
- [ ] **Cache commands:** `jpm cache size` and `jpm cache clean`.
- [ ] **Workspace-wide commands:** run a script or command in every workspace module, with a
      `--filter` like pnpm's.
- [ ] **Testing:** `jpm test --watch`, and JUnit XML reports for CI.
- [ ] **Actionable errors:** every error says what went wrong, where (file and line for manifest
      errors), and what to do next; no stack traces unless `--verbose`.
- [ ] **Supply-chain checks:** verify checksums (and signatures where published) of every downloaded
      artifact, store hashes in `project.lock`, and fail `--frozen` installs on a mismatch.
- [ ] **`jpm audit`:** report dependencies with known vulnerabilities (OSV database).
- [ ] **SBOM:** generate CycloneDX or SPDX from the lockfile, as a command or an extension.
- [ ] **Reproducible builds:** fixed timestamps and entry order in jars, so the same sources and lock
      give byte-identical output; checked in CI.
- [ ] **Performance budget:** measure `jpm install` and no-op `jpm build` on the fixtures in CI and fail
      on large regressions.
- [ ] **Windows parity:** run the full test suite on Windows in CI, not only the native smoke test.
- [ ] **Extension registry:** a list of known extensions (jpm-publish, jpm-changeset, …) and a template
      repository for writing new ones.
- [ ] **Changelog for jpm itself:** use jpm-changeset on the jpm repositories once it exists.
- [ ] **Document the versioned schema URL:** every release already ships `project.schema.json`, so
      `releases/download/<version>/project.schema.json` is a stable URL per version. The README only
      mentions `releases/latest/…`; document the pinned form as the replacement for `jpm schema`.

---

## Plan

How the roadmap above gets done: decisions first, then milestones in order. Each milestone ends in a
release. Inside a milestone, items with no **Needs:** can be done in parallel.

### Decisions (Phase 0, settled)

- **API shape: a protocol, with a client jar.** There is exactly one jpm on a machine, the globally
  installed one. Nothing ships its own copy: not jpm-intellij, not extensions, not the client jar.
  - The `jpm` binary serves a versioned protocol: JSON-RPC over stdin/stdout from `jpm api`, framed
    like LSP.
  - `dev.jpm:jpm-api` on Maven Central holds the model records, the shared JSON/JSONC reader and
    writer, and a client that starts the global `jpm api`. It holds no resolution logic.
  - When no jpm is installed, jpm-intellij offers to install one globally, using the same steps as
    `install.sh`/`install.ps1`. It never bundles one.
- **Java targets.**
  - The client jar targets the Java version of the JetBrains Runtime in the oldest IDE build that
    jpm-intellij supports (`sinceBuild`, 261 today). That is Java 21 today, matching jpm-intellij's
    `jvmToolchain(21)`.
  - The client jar moves up only when jpm-intellij's `sinceBuild` moves to an IDE build on a newer
    runtime.
  - jpm itself stays on `"java": "21"`.
- **Java support matrix.** It applies to the projects jpm builds: targets 8, 11, 17, 21, 25 and the
  current feature release, built and tested with those JDKs. It does not apply to the client jar.
- **Stability: none while jpm is in development.**
  - Breaking changes are allowed in any release, with no deprecation period and no backwards
    compatibility.
  - This covers the CLI, `project.jsonc`, `project.lock`, the protocol and the client jar.
  - Old forms are removed in the same change that replaces them. There are no warnings and no
    converters.
  - The protocol handshake still carries a version number. A client that does not match the
    installed jpm gets a clear error that names both versions, not undefined behavior.
  - A stability policy is decided before jpm 1.0. That release has no date and is not part of this
    plan.
- **Layering.** As proposed in Phase 0. `module-info.java` in the `api` module exports only
  `dev.jpm.api…`. The jpm module exports nothing.
- **`//DEPS`.** Removed. Script dependencies live in `scripts` entries in `project.jsonc`.

### Ground rules for every change

- **Workflow: local branches, one push.** No pull requests, and no push per step.
  - Each roadmap item, or each part of one, gets its own local branch.
  - Before a branch is merged, the tests pass on the machine: `bin/jpm build`, `bin/jpm test`, the
    end-to-end tests, and the local checks (schema check, ratchets).
  - A branch whose tests pass is merged into the local `main` and deleted.
  - `main` is pushed once, after every branch worked on has been merged.
  - Some checks only run in CI: other operating systems, native builds, the JDK matrix. If one of them
    fails after a push, it is fixed on `main` before any new work starts.
- One script, `bin/check`, runs every check CI runs that can run locally. It is replaced by
  `jpm check` once that exists (Milestone 4).
- Restructuring changes behavior only where the roadmap says so. The end-to-end tests from Milestone 1
  are the safety net, so they come before any move.
- Ratchets instead of big bangs: comment count, missing Javadoc, `System.out` uses and network tests
  each get a check, run locally and in CI, with a stored baseline. The baseline may only go down.
- A roadmap item is ticked in the commit that finishes it.

### Milestone 1: safety net and foundations (release 0.9)

Builds the test and tooling base that the restructure depends on.

1. **End-to-end tests first.** One test set runs the `jpm` binary on `fixtures/` (build, test, bundle,
   info, import). Add fixtures for every command that has none, and record today's output as the
   expected result.
2. **Hermetic tests.**
   - A local Maven repository server and Git fixtures under `test/`, shared through a helper.
   - Move the network-dependent classes onto them: `NetTest`, `TransientFailureTest`,
     `RepositoriesTest`, `UpgradeTest`, `ImportTest`, `KotlinTest`, `SmallGapsTest`,
     `WorkspaceInheritanceTest` and the rest.
   - Live tests against Maven Central get a tag, and a separate CI job runs them.
3. **`bin/check`:** runs locally what CI runs: build, unit and end-to-end tests, the schema check and
   the ratchets. CI calls the same script, so the two cannot drift apart.
4. **Coverage report in CI**, published as an artifact. It finds dead code before the restructure.
5. **Logger** (Phase 1).
   - `dev.jpm.log`, with no dependencies: levels, the color rules, `--color`, `NO_COLOR` /
     `FORCE_COLOR` / `JPM_COLOR`, `--quiet` and `--verbose`, and progress output only on a terminal.
   - Replace `Log` and the `System.out`/`System.err` calls in the 9 files that have them.
   - A ratchet check bans new ones.
   - Fold in **actionable errors** from the suggestions: one error type with message, location and
     hint, and stack traces only with `--verbose`.
6. **Comments** (Phase 1).
   - Run the categorise-and-act task in jpm, jpm-publish, jpm-format and jpm-intellij.
   - Decision records go to `docs/adr/NNNN-title.md`. Excuses become GitHub issues.
   - The lint rule starts as a CI script with a baseline file, and becomes a jpm-format rule once
     Milestone 5 makes that possible.
   - Add the `/delete-comment` skill in `.claude/skills/`.
   - `project.jsonc` comments are out of scope: they are documentation of the manifest, not code.
7. **Contributor docs** (Phase 1). Write `CONTRIBUTING.md` and `AGENTS.md` in each repository, with
   the decisions and ground rules above. Update them at the end of every milestone.

### Milestone 2: restructure (release 0.10)

**Needs:** Milestone 1 items 1 to 4.

1. **Workspace layout.** Done.
   - The jpm repository is a workspace with an `api` module (`dev.jpm.api`, published as
     `dev.jpm:jpm-api`) and a `cli` module (the CLI and everything else). A workspace root lists modules
     only, so the CLI moved into `cli/` rather than staying in the root.
   - Both modules have a `module-info.java`; `cli` exports nothing. The `api` module is compiled with
     `-Xdoclint:all/protected -Werror`, so missing Javadoc fails the build.
2. **Command registry.**
   - Split `cli/Commands.java` into one class per command, registered in one place.
   - Generate `jpm help`, the README command table and **shell completions** (`jpm completions
     <shell>`, from the suggestions) from the registry. A CI check fails when the README table is out
     of date.
3. **Split large classes.** Split `GradleImport`, `Resolver` and `Manifest` by responsibility, one
   one branch each.
4. **One of each.**
   - JSON/JSONC goes into `api`, because the protocol needs it there anyway.
   - One HTTP stack: merge `util/Http` and `util/Net`.
   - One version module with a single entry point.
5. **Inline single-implementation abstractions.** Use the coverage report and the class graph to find
   them.
6. **Layers.**
   - Move code into `dev.jpm.<area>` (internal API) and `dev.jpm.<area>.impl`.
   - Javadoc on public and internal API, none on `impl`.
   - The build fails on missing Javadoc in `dev.jpm.api`, and on any `impl` class referenced from
     another area. The second is a check over the compiled classes.
7. **Tests follow the layout.** Unit tests per area. Merge or delete grab-bag and implementation-detail
   tests. Shared helpers go in one test package.

### Milestone 3: compiler agnostic, every Java version (release 0.11)

**Needs:** Milestone 2 item 6, so the changes land in the new layout.

1. **No source parsing.**
   - Read `module-info.class` instead of using regexes on `module-info.java`, or pass
     `--module-source-path` and let javac resolve.
   - Decide `--release` against `-source/-target` from the arguments, not from javac's error text.
   - Write generated `Defines` and bundle launchers as class files, so a Kotlin-only module needs
     no Java source generation.
   - Add the CI check against code that opens `.java` files.
2. **`//DEPS` to manifest.**
   - Add `scripts` entries with their own `dependencies`, in the schema and the resolver.
   - Remove `//DEPS` support in the same change.
3. **Java matrix.**
   - Fixtures for each target (8 through current), including a module project and a plain one.
   - A CI matrix over JDK × target.
   - Clear errors for combinations that cannot work, such as modules on 8.
   - A table in `docs/` of the supported versions and their vendor end-of-life dates, reviewed every
     release.
4. **Reproducible builds** and the **performance budget** (from the suggestions). Both are cheap to
   add now that the build code is in one place.

### Milestone 4: protocol, extensions, jpm check (release 0.12)

**Needs:** Milestone 2.

1. **Protocol spec** in `docs/protocol.md`.
   - Handshake with version negotiation.
   - Methods: project model, modules, folders, scripts, dependencies with scopes and sources, every
     classpath, defines, manifest schema, extension commands, and check results.
   - Errors in the same shape as `jpm check` problems.
   - Review the spec before any code.
2. **`jpm api` server** in jpm, with protocol tests in the end-to-end set.
3. **Client jar** `dev.jpm:jpm-api`.
   - Finds the global jpm, starts it and checks the version.
   - Published to Maven Central with Javadoc and sources. Release automation lives in CI next to the
     binary release.
4. **jpm-publish** moves to the client jar and drops its own `Json.java`.
5. **jpm-intellij** moves to the client jar.
   - Replace `Jpm.run(... "info", "--json", "--ide")` in `JpmSync` and in the tests.
   - When no global jpm is installed, offer to install one.
6. **Project-level extensions.**
   - Extensions are declared in the root `project.jsonc` and pinned in `project.lock`.
   - `jpm add`/`jpm remove` without `-g`, with the extension marker for detection.
   - `jpm install` installs declared extensions.
   - Warn about settings for undeclared extensions.
   - A declared extension takes precedence over a global one.
7. **Extension descriptor.**
   - Each extension describes its commands, options and checks in its own manifest.
   - jpm serves the descriptors through the protocol, so IDEs never run an extension just to list
     it.
   - The jpm-intellij toolbar is built from it.
   - Add an extension template repository (**extension registry** suggestion).
8. **`jpm check`.**
   - Runs jpm's checks, then the declared extensions' checks, then the tests.
   - Every check runs even when one fails.
   - Problems carry a severity and a location.
   - `--no-test`, `--fail-on-warning`, SARIF and JUnit XML output, and output grouped by check.
   - Add **unused dependencies** and **duplicate classes**, read from compiled classes.
   - **Missing paths** is one shared check for every field that names a file (Phase 5, *Editing
     project.jsonc*).
9. **Remove the old commands.**
   - `jpm info --json/--ide/--classpath` and `jpm schema` are removed in this release.
   - Release jpm, jpm-publish and jpm-intellij together, so nothing calls the removed commands.
   - Document the versioned schema URL in the same change.
   - Plain `jpm info` stays.

### Milestone 5: ecosystem (later 0.x releases)

**Needs:** Milestone 4 for anything that adds a check or uses the protocol.

The tracks below are independent. Each one is released from its own repository.

- **jpm-changeset.** The repository is empty. Build in this order: `add`, `status` (with its check),
  `version`, `publish` through jpm-publish, then prerelease mode and groups. The GitHub Action comes
  last. Once it works, use it for jpm's own changelog.
- **jpm-format.**
  - Formatter parity is measured against IntelliJ on a corpus of real projects, with a stored diff
    count as a ratchet. Then lint parity, then the LSP.
  - Add `format --check` to `jpm check`.
  - Replace the comment-lint script from Milestone 1 with a jpm-format rule.
- **jpm-intellij.**
  - The migration UI (editable manifest, warnings first, layout switch, cleanup of old build files,
    scan for leftover Gradle/Maven use).
  - Then `project.jsonc` editing: navigation, renames, completion and inlay hints, update inspection,
    hover docs, and diagnostics from `jpm check` over the protocol.
  - Then run configurations and the dependency graph.
  - The `.gitignore` fix is done in `jpm init`/`jpm import`, so it ships with any jpm release.
- **Licenses.** Generate the SPDX list into the schema at build time from `licenses.json`. Add the
  check, the import support and license file creation. Do this in jpm, not in an extension.
- **Renovate.** A regex-manager preset first, used on the jpm repositories, then a native manager
  upstream. Document Renovate as the supported option, instead of Dependabot.
- **Remaining suggestions:**
  - lockfile conflict resolution;
  - private repository credentials;
  - `jpm cache size|clean`;
  - workspace-wide commands with `--filter`;
  - `jpm test --watch`;
  - checksums in `project.lock`, then `jpm audit`, then SBOM;
  - `jpm doctor`;
  - the full test suite on Windows in CI.

  Order them by user demand. Supply-chain checksums come first, because they change the lockfile
  format, which is cheaper to do early.

### Milestone 6: user documentation (alongside Milestone 5)

**Needs:** the command registry (Milestone 2) and the protocol (Milestone 4), so that the reference
can be generated.

1. Rewrite the README for users.
2. Move the reference material into `docs/`, generated from the schema and the command registry.
3. Move release and CI details into `CONTRIBUTING.md`.
4. Keep `MIGRATION.md` and link it from the README.
5. A docs site, once `docs/` has been stable for a release.

### Risks

- **Protocol scope creep.** The first protocol release covers only what `jpm info --json/--ide/--classpath` and `jpm schema`
  give today, plus extension descriptors and check results. Everything else comes in later
  releases.
- **Restructure without behavior change.** If an end-to-end test changes its expected output during
  Milestone 2, that is a bug, unless the commit message says otherwise and why.
