# JPM — Java Package Manager

A single CLI for Java in the spirit of Bun, Cargo and Go: dependencies, builds, running, tests,
patches and build-time defines, driven by one `project.jsonc`. The command is `jpm`.

This covers **all three roadmap steps** of the spec: the build tool itself (manifest, lockfile,
resolution from Git and Maven Central, building, running, defines, env files, patches, workspaces,
tests, scripts, hooks, extensions), JDK/GraalVM management with native builds (`jpm java`, `jvm`,
`java` that follows your project, `build --native`), and importing and building existing Maven and
Gradle projects (`jpm import`, and Git dependencies without a `project.jsonc`).

## Install

```sh
curl -fsSL https://github.com/java-package-manager/jpm/releases/latest/download/install.sh | sh      # Linux, macOS
irm https://github.com/java-package-manager/jpm/releases/latest/download/install.ps1 | iex           # Windows
```

The scripts:

- download the native binary for your platform and check it against the release's `SHA256SUMS`;
- put `jpm` and `jvm` into `~/.jpm/bin`;
- keep that folder first on your PATH: one marked line in your shell's startup file (`~/.zshrc`,
  `~/.bashrc` and on macOS `~/.bash_profile`, or `~/.profile`) sources `~/.jpm/env`, which puts it
  first and moves it back to the front before every prompt, so a JDK that something else prepends later
  cannot shadow the shims (fish gets `conf.d/jpm.fish`; Windows gets the folder in the user PATH, but
  the system PATH comes first there). `JPM_NO_MODIFY_PATH=1` skips this;
- create `java`, `javac`, ... shims there, so `java` runs your project's JDK (see
  [JDKs and GraalVM](#jdks-and-graalvm)). Outside projects nothing changes until you run `jvm use`.
  `JPM_NO_SHIMS=1` skips this.

Open a new terminal afterwards. `JPM_VERSION=0.3.0` installs that version instead of the latest.

**Upgrading:** `jpm upgrade` replaces jpm with the latest release (checksum-verified, in place) and
refreshes the shims. `jpm upgrade --check` only reports; `jpm upgrade 0.3.0` installs a given version.

**Prereleases** (canaries) are versions like `0.13.0-beta.0`, released from jpm-changeset's prerelease mode.
`jpm upgrade` and the install scripts skip them; `jpm upgrade --canary` (and `JPM_VERSION=canary` for the
install scripts) installs the newest prerelease or release, whichever is newer, and `jpm upgrade` from a
prerelease moves to the release once it is out. `jpm upgrade <version>` installs a given one.

`jpm version` says when a newer release is out, and on a prerelease also when a newer prerelease is (on
stderr, after the version, so scripts reading the version are unaffected).

**Releases** are built by CI for Linux (x64, arm64), macOS (arm64, x64) and Windows (x64), when the main
branch gets a version that has no release yet: merging the "Version packages" pull request that
[jpm-changeset](https://github.com/java-package-manager/jpm-changeset) keeps (see CONTRIBUTING.md) tags that
version (plain, no `v` prefix) and releases it. Every push and pull request gets the same builds and tests,
so a release is known to pass before it is merged; only a release publishes. There is no tag to push by
hand. Nothing is published unless every platform passes, and a version is never released twice.
Versions like `1.0.0-rc1` become GitHub prereleases, which `jpm upgrade` and the install scripts skip.

## Getting started

```sh
# or from source:
./bootstrap.sh            # compiles jpm with javac, then jpm builds itself into cli/dist/ and api/dist/
export PATH="$PWD/bin:$PATH"

mkdir hello && cd hello
jpm init                  # project.jsonc, src/, test/, .gitignore
jpm add 'com.google.guava:guava@[33.0,34.0)'
jpm add --dev org.projectlombok:lombok@1.18.34
jpm start                 # build and run main
jpm test                  # JUnit 5 is added automatically
jpm build --release       # dist/hello-0.1.0.jar, runs postbuild:release hooks
```

jpm has no dependencies of its own and compiles to a native binary with GraalVM
(`jpm build --native`; jpm installs GraalVM if needed). It builds and tests itself: `bin/jpm test`.
On Windows use `bin\jpm.cmd` for the jar build.

CI (`.github/workflows/ci.yml`) runs the tests and builds and smoke-tests the native binary on Linux
(x64, arm64), macOS (arm64, x64) and Windows (x64); the binaries are uploaded as workflow artifacts.

## Commands

<!-- commands: generated from the command registry, `JPM_UPDATE_EXPECTED=1 bin/jpm test dev.jpm.cli.impl.CommandsTest` rewrites it -->

| Command | |
|---|---|
| `init [name]` | Create a project; `--license <SPDX id>` sets its license and writes `LICENSE` (as `jpm license` does) |
| `add <dep>[@version]` | Add dependencies (`--dev`, `--test`, `-r <url>` to add a Maven repository). An extension (a Git project with `"extension"` in its manifest) is declared in the root's `"extensions"` instead; `-g` installs one globally. Without a version: latest release (Maven) or `^latest-tag` (Git) |
| `remove <dep>` | Remove dependencies, or a declared extension by name or key (`-g` removes a global extension) |
| `install` | Resolve dependencies and declared extensions and write `project.lock`; `--frozen` fails if the lock is stale (CI) |
| `update [dep...]` | Re-resolve within ranges; `--latest` rewrites the manifest past them. `update -g [name…]` updates global extensions |
| `outdated` | Show dependencies with newer versions |
| `audit` | Report the locked dependencies with known vulnerabilities, from the OSV database: id, severity, the version that fixes it and how the project gets it; exits 1 when there are any. `--ignore <id>` (repeatable) accepts one, `--json` prints them as JSON, `--frozen` |
| `sbom` | Write a CycloneDX 1.6 software bill of materials (JSON) for the locked dependencies: package URLs, SHA-256 hashes, licenses and the dependency graph; the same lock gives the same file. `-o <file>` writes it to a file instead of stdout, `--frozen` |
| `why <dep>` | Show why a dependency is in the graph |
| `info` | Describe the project: each module's version and folder, and its dependencies with what they resolve to. Programs use the jpm protocol instead (`jpm api`, [docs/protocol.md](docs/protocol.md)) |
| `import [dir]` | Create `project.jsonc` from a Maven or Gradle build (`--dry-run`, `--static`, `--force`) |
| `license [id]` | Show the project's license and license file; `license <SPDX id>` sets `"license"` and writes `LICENSE` from SPDX's text with the year and the author (`--author`, else git's `user.name`) filled in; `--force` replaces a license file |
| `build` | Build into `dist/` (`--release`, `--profile <name>`); in a workspace, every module, the one you are in, or the modules named; `--filter <pattern>` (repeatable) picks workspace modules: a name or glob (`app`, `lib-*`), a folder (`./libs/*`), `<pattern>...` with what it depends on, `...<pattern>` with what depends on it, `!<pattern>` leaves modules out |
| `build --bundle` | Also build `dist/<name>-<version>-bundle.jar` with the runtime dependencies: a runnable jar-in-jar with isolated modules for a module with a `main`, one flat jar (like a shadow jar) for one without, such as a Paper or Velocity plugin; `--flat` makes it flat either way. `"package": "bundle"` makes it the default |
| `build --native` | Also build a native executable of `main` with GraalVM into `dist/` |
| `start [entry] [-- args]` | Build, then run `main` with the production env |
| `start --trace` | Run under the native-image tracing agent, recording configuration for native builds |
| `dev [entry] [-- args]` | Run `main` with the development env: a change to the module's code is recompiled and reloaded into the running JVM (method bodies; any change on JetBrains Runtime), anything else, or a change that cannot be reloaded, restarts it; `--restart` always restarts |
| `test [filter...]` | Run tests on the JUnit Platform: a class, `Class#method` or a pattern selects tests, `--tag` and `--exclude-tag` select by JUnit tag; `--reports <dir>` writes JUnit XML reports for CI (one folder per module in a workspace); in a workspace, `--filter <pattern>` (repeatable) picks workspace modules: a name or glob (`app`, `lib-*`), a folder (`./libs/*`), `<pattern>...` with what it depends on, `...<pattern>` with what depends on it, `!<pattern>` leaves modules out |
| `check [check...]` | Run jpm's checks (manifest, dependencies, paths, license, compile, unused-dependencies, duplicate-classes), the checks of the extensions the project declares, then the tests, and report every problem; `--no-test`, `--fail-on-warning`, `--exclude-tag <tag>`, `--sarif <file>`, `--junit <file>`, `--frozen` |
| `run <script> [args]` | Run a script from `scripts` (no shorthand); `<module>:<script>` runs a workspace module's script from anywhere in the workspace |
| `run -r <script> [args]` | Run a script in every workspace module that has it, in build order, stopping at the first that fails; `--filter <pattern>` (repeatable) picks workspace modules: a name or glob (`app`, `lib-*`), a folder (`./libs/*`), `<pattern>...` with what it depends on, `...<pattern>` with what depends on it, `!<pattern>` leaves modules out (and implies `-r`) |
| `clean` | Delete build output |
| `patch <dep>` | Unpack a dependency's source for editing |
| `patch --commit <dep>` | Save your edits to `patches/` and record them in the manifest |
| `java list [--available]` | Installed JDKs (`*` marks what `java` runs here), or what can be installed (`--available [vendor]`) |
| `java install <java>` | Install a JDK or GraalVM into `~/.jpm/jdks` (`21`, `temurin-17`, `graalvm-21`, ...) |
| `java remove <java>` | Remove a JDK jpm installed |
| `java use <java>` | Set `"java"` in the manifest, installing it if needed |
| `jvm use <java>` | Choose the JDK that `java` runs outside projects (shims in ~/.jpm/bin) |
| `jvm current` | Which JDK `java` runs here, and why. `jpm java …` and `jvm …` are the same commands |
| `jvm exec [java] <cmd>` | Run one command with a JDK |
| `cache size` | Show how much space each of jpm's caches in `~/.jpm` uses (Maven packages, Git dependencies and their builds, patched jars, Gradle distributions, JDK downloads) |
| `cache clean [cache...]` | Delete jpm's caches, or only the ones named (`repository`, `git`, `builds`, ...: see `jpm cache size`); what a project needs is downloaded or built again. Installed JDKs, global extensions and shims are kept |
| `doctor` | Check this machine, not the project: PATH order, the shims, the shell setup, installed JDKs and the default one, jpm's home and caches, and whether the Maven repositories answer; prints what to fix and exits 1 on an error. `--offline` skips the network |
| `upgrade [version]` | Upgrade jpm itself to the latest release (`--check` only reports, `--canary` also takes prereleases, such as `0.13.0-beta.0`) |
| `version` | Show jpm's version (also `--version`), and whether a newer one is out |
| `help` | Show this list of commands (also -h, --help) |
| `completions <shell>` | Print shell completions: `jpm completions bash > ~/.local/share/bash-completion/completions/jpm`, and the same for `zsh`, `fish` and `powershell` |
| `api` | Serve the jpm protocol (JSON-RPC on stdin and stdout, see [docs/protocol.md](docs/protocol.md)) for IDEs, extensions and tools; the client `dev.jpm:jpm-api` starts it |
| `<anything else>` | Runs the extension `<name>`: the one the project declares, else `jpm-<name>` from `~/.jpm/bin` or `PATH` (see [Extensions](#extensions)) |

<!-- /commands -->

Dependency arguments: `group:artifact@1.2.3`, `group:artifact:1.2.3`, `'group:artifact@[1.0,2.0)'`,
`github:user/repo@^1.2`, `github:user/repo#main`, `github:user/repo/module@a1b2c3d`, `gradle:api@9.8.0`
(see [Gradle plugins](#gradle-plugins)), `ext:paperweight/server@26.3` (see [Dependencies from extensions](#dependencies-from-extensions)).

## Manifest

```jsonc
{
  "name": "my-app",
  "version": "1.3.0",
  "java": "graalvm-21",   // installed automatically if missing
  "target": "17",         // bytecode target (--release), defaults to java
  "main": "com.example.App",          // or { "cli": "...", "server": "..." } → jpm start server
  "runtime": {
    "com.google.guava:guava": "[33.0,34.0)",
    "github:user/repo/api": "^1.2"
  },
  "dev":  { "org.projectlombok:lombok": "1.18.34" },
  "test": { "org.junit.jupiter:junit-jupiter": "5.11.0" },
  "define": {
    "API_URL": { "type": "string", "env": "API_URL" },
    "RETRIES": { "type": "int", "value": 3 },
    "DEBUG":   { "type": "boolean", "env": "DEBUG", "default": false },
    // values for defines declared by a dependency:
    "github:user/repo/api": { "API_KEY": "..." }
  },
  "scripts": { "format": "scripts/format.sh", "upload-mappings": "scripts/UploadMappings.java" },
  "hooks":   { "postbuild:release": "upload-mappings" },
  "patches": { "com.example:old-lib": "patches/old-lib.patch" }
}
```

**Versions** can be written in Maven's, Gradle's or npm's syntax, for Maven and Git dependencies alike:

| Syntax | Examples | Means |
|---|---|---|
| exact | `1.2.3`, `=1.2.3`, `1.2.3!!` | that version (for Git dependencies a bare `1.2` is npm's `1.2.x`) |
| Maven range | `[1.0,2.0)`, `[1.5,)`, `(,1.0]`, `[1.0]`, `(,1.0],[1.2,)` | `[` `]` include the end, `(` `)` exclude it |
| Gradle range | `]1.0,2.0[`, `[1.0,2.0[` | reversed brackets exclude the end |
| Gradle prefix | `1.+`, `1.2.+`, `+` | the newest version starting with `1.`, `1.2.`, or any; as in Gradle, snapshots and pre-releases count |
| caret / tilde | `^1.2`, `^0.2.3`, `~1.2.3`, `~1` | compatible changes; `~` patch changes (minor for `~1`) |
| comparisons | `>=0.1`, `>1.0 <=2.0`, `1.0 - 2.0` | combined with spaces; a hyphen gives both ends |
| wildcards | `1.x`, `1.2.*`, `*` | any value in that position |
| alternatives | `^1 \|\| ^2` | either range |
| newest | `latest.release`, `RELEASE`, `latest.milestone`, `latest.integration`, `LATEST` | the newest release; with milestones; with snapshots too |
| strict, preferring | `[1.0,2.0)!!1.5` | that range, 1.5 when it is published |

Ranges compare versions the way Maven orders them, so qualifiers work: `^33` matches `33.4.8-jre`.
Pre-releases (alpha, beta, milestone, rc) and snapshots are only chosen when the range names one, as in
`^1.3.0-beta`, or asks for them (`latest.milestone`, `latest.integration`, Gradle's `+` versions).

A dependency is a version string, or an object with options:

```jsonc
"runtime": {
  "org.lwjgl:lwjgl:natives-linux": "3.3.4",                              // classifier: third part of the key
  "org.slf4j:slf4j-simple": { "version": "2.0.16", "runtimeOnly": true }, // on the runtime classpath only
  "com.example:lib": { "version": "1.2", "exclude": ["commons-logging:commons-logging", "org.unwanted:*"] }
},
"dev": {
  "io.papermc.paper:paper-api": { "version": "26.3.build.+", "api": true }  // consumers compile against it too
}
```

What reaches the projects and modules that depend on this one, like Gradle's configurations:

| Declared as | Gradle | Here | Consumers compile | Consumers run, bundles |
|---|---|---|---|---|
| `runtime` | `api` | compile, run | yes | yes |
| `runtime` with `"runtimeOnly": true` | `runtimeOnly` | run | no | yes |
| `dev` | `compileOnly` | compile | no | no |
| `dev` with `"api": true` | `compileOnlyApi` | compile | yes | no |

`"api": true` is for APIs a platform provides, such as a server API that consumers' code uses too. Published
with jpm-publish, it is in the Gradle Module Metadata (compile only) and `provided` in the POM.

A dependency can be in both `dev` and `test`, with the same version: compiled against, run with in tests,
not shipped. Use this for a platform API that tests run against, like Gradle's `compileOnly` plus
`testImplementation`. `jpm add --dev` and `jpm add --test` keep the other entry and give it the same version.
A `runtime` dependency in `test` too is ignored there, with a warning, since tests have every runtime
dependency already. In `dev` too, it is ignored with a warning when the versions match and is an error
when they do not.

Exclusions apply to everything the dependency brings in, as in Maven. `jpm add g:a:classifier@1.0` adds a
classified jar.

A `test` dependency on a workspace module can bring that module's compiled tests too, like Gradle's test
fixtures: `"test": { "workspace:lib": { "version": "*", "tests": true } }` puts `lib`'s test classes (its base
classes and helpers) on this module's test classpaths. `lib`'s tests are not run with this module's.

**Repositories.** Maven Central is always searched. Other Maven repositories go in `"repositories"`
(in a workspace, on the root manifest), and `jpm add -r <url> <dependency>` adds one for you:

```sh
jpm add -r https://repo.example.dev/releases com.example:lib:1.0.0
```

A repository that needs a login reads it from the environment, never from `project.jsonc`:
`JPM_REPO_<HOST>_USERNAME` and `JPM_REPO_<HOST>_PASSWORD` (basic authentication), or `JPM_REPO_<HOST>_TOKEN`
(a bearer token), where `<HOST>` is the repository's host in upper case with every character other than a
letter or digit as `_`. For `https://repo.example.dev/releases` that is `JPM_REPO_REPO_EXAMPLE_DEV_USERNAME`
and `_PASSWORD`; for GitHub Packages (`https://maven.pkg.github.com/<owner>/<repo>`),
`JPM_REPO_MAVEN_PKG_GITHUB_COM_USERNAME` and a token as `_PASSWORD`. Credentials are sent only over HTTPS (or
to this machine), and not along a redirect to another host. A repository that answers 401 or 403 gets an
error that names the variables to set.

Other fields:

- `package`: `"jar"` (the default) or `"bundle"`, see [Bundles](#bundles-jar-in-jar-isolation)
- `layout`: `"maven"` for `src/main/java`, `src/main/resources`, `src/test/java`, `src/test/resources`
  (set by `jpm import`)
- `sources`, `resources`: several source or resource folders, replacing the layout's, e.g.
  `"sources": ["src/main/java", "src/generated/java"]` for committed generated code
- `group`; `visibility` (`public`/`internal`, workspaces); `isolated` (a workspace module with its own resolution); `workspace` (module list, on a workspace root)
- `repositories` (extra Maven repositories); `defineClass` (name of the generated class)
- `javacArgs`, `nativeArgs` (extra flags for javac and native-image); `-J` entries in `javacArgs`
  go to the JVM running javac, e.g. the `-J--add-exports=jdk.compiler/...=ALL-UNNAMED` ErrorProne needs
- `testJvmArgs`: options for the JVM running the tests, e.g. `["-Xmx2g"]`
- `kotlin`: the Kotlin version for Kotlin sources, see [Kotlin](#kotlin)
- `exclude`: packages left out of everything the `runtime` and `dev` dependencies bring in, like an exclusion
  on each of them (Gradle's `configurations.all { exclude(...) }`), e.g. `["org.slf4j"]` for what the platform
  provides; `test` dependencies keep theirs. A workspace module adds its own to the root's
- `filter`: resources copied with each `${name}` replaced, e.g. `"filter": ["app.properties"]`. Values come from,
  later ones winning: the module's `NAME` and `VERSION`; the fields of the workspace root's project.jsonc, then the
  module's (`${name}`, `${version}`, `${description}`, nested ones dotted: `${extensions.publish.repo}`, array items by
  index: `${javacArgs.0}`); the `*.properties` files next to the root's project.jsonc (such as `gradle.properties`),
  then those next to the module's; and the module's define values. Unknown names are left as they are
- `description`, `url` (used by publishing)
- `license`: an SPDX license id (`MIT`) or expression (`MIT OR Apache-2.0`); the schema lists every id.
  `jpm license <id>` sets it and writes `LICENSE` from SPDX's text, `jpm init --license <id>` does the same
  for a new project, `jpm import` takes it from the POM or the license file, and `jpm check` reports ids
  that are not on the SPDX list and a `"license"` that does not match the license file
- `extensions`: the extensions the project uses, by dependency key with a version, and their settings by
  name, which jpm passes through untouched: `"extensions": { "github:java-package-manager/jpm-publish": "^0.2",
  "publish": { ... } }`. See [Extensions](#extensions).
- `extension`: marks an extension's own manifest (`"extension": {}`), so `jpm add` declares it as one

Unknown fields are errors, and every error names the file and line.

Layout: `src/` with `src/assets/` (resources, copied into the jar as is), `test/` with `test/assets/` (test
resources), `scripts/`, `patches/`, `.cache/` (build cache, not committed), `dist/` (jars), `.changeset/` (reserved).

`src/assets/demo/icon.png` is `demo/icon.png` in the jar; service files go in
`src/assets/META-INF/services/`. `.java` files inside the assets folders are not compiled.

## Editor support

`project.jsonc` has a JSON Schema, [`cli/src/assets/dev/jpm/project.schema.json`](cli/src/assets/dev/jpm/project.schema.json),
built into jpm and attached to every release at a stable address:

```
https://github.com/java-package-manager/jpm/releases/latest/download/project.schema.json
```

`jpm init` and `jpm import` write it into new manifests as `"$schema"`, which VS Code, IntelliJ IDEA, Zed,
Neovim (with a JSON language server) and most other editors pick up for completion, documentation on hover
and validation. Add the line to an existing manifest to get the same:

```jsonc
{
  "$schema": "https://github.com/java-package-manager/jpm/releases/latest/download/project.schema.json",
  "name": "my-app"
}
```

`releases/latest` always describes the newest jpm. To describe exactly the fields of one jpm, pin its
version; that address never changes:

```
https://github.com/java-package-manager/jpm/releases/download/0.12.0/project.schema.json
```

Editors that want the schema of the jpm they run ask it over the jpm protocol (`schema/manifest`, see
[docs/protocol.md](docs/protocol.md)); the [IntelliJ plugin](https://github.com/java-package-manager/jpm-intellij)
does, so there every `project.jsonc` is covered without the line. (`jpm schema` did this before jpm 0.12.0.) The schema also describes the settings
of the `publish` extension under `"extensions"`.

## How it works

**Resolution.** Maven and Git packages live in one graph and conflicts are settled the same way for
both: every hard constraint must hold — anything declared in a manifest, and any range anywhere — and
among the versions that satisfy them the highest wins. Plain versions inside POMs are soft. Locked
versions are kept while they still satisfy the constraints, so editing one dependency does not move
others. POMs are read with parent inheritance, property interpolation, `dependencyManagement`, imported
BOMs, exclusions, optional/provided/test scopes, relocation and profiles: a profile is active by `jdk` (the
project's `"java"`, or jpm's own when it has none), `os`, `property` (system properties, and the environment
as `env.X`) and `file` (POMs on disk only), all that it gives holding, or `activeByDefault` when no other
profile of that POM is; its properties, managed versions and dependencies apply. Changing `"java"` resolves
again, since it can change which profiles are active. For libraries published by Gradle, the
Gradle Module Metadata next to the POM decides what is needed when: `compileOnlyApi` dependencies (which
the POM lists as `compile`) are on the compile classpath only, never at run time or in bundles, and
`implementation` and `runtimeOnly` dependencies are on the runtime classpath only.
The resolver is jpm's own rather than an embedded Maven Resolver or Coursier, so that jpm has no
dependencies and builds as a native image.

**Git dependencies** go through the system `git` (so SSH keys and credential helpers work), into bare
mirrors under `~/.jpm/git`. Versions are ranges in any of the syntaxes above matched against tags,
exact tags, branches (`main`, or `#main` to say it is a branch; a tag of the same name wins over `main`) or
commits. A tag must match the version in that commit's manifest (when following a branch, a tag that does
not is ignored); untagged commits are versioned like `1.3.0+a1b2c3d`. The `"repositories"` a Git
dependency's manifest lists are searched too, after the project's own, for what it depends on. Git packages are built from source with the consuming project's resolved versions
and cached in `~/.jpm/builds`, keyed by commit, patch, define values, classpath and JDK. A repository
without a `project.jsonc` is imported from its `pom.xml` or Gradle build (see
[Importing](#importing-maven-and-gradle-projects)); one with neither fails with a clear error.

Timestamped snapshot versions (`0.1-20240720.200737-2`) can be asked for by name, as in Gradle: they are read
from the folder of their `-SNAPSHOT` version.

**Lockfile.** `project.lock` is sorted JSONC with one line per package: exact version, tag and commit
for Git, a SHA-256 for Maven jars (verified on every install) and for their POMs (`"pom"`, verified when
resolving), and its edges. It is reused as long as the declared dependencies are unchanged.

**Merge conflicts in `project.lock`.** Do not resolve them by hand: `jpm install` reads both sides of every
conflict, starts from the higher version of each package, resolves again and writes a clean lockfile.
`jpm install --frozen` fails on a lockfile with conflict markers, so CI never builds from one.

**Vulnerabilities.** `jpm audit` asks the [OSV](https://osv.dev) database about every locked package (Maven
packages by name and version, Git packages by commit) and prints each known vulnerability with its id and
CVE, its severity, the version that fixes it and how the project gets the package; it exits 1 when there is
one, so it can run in CI. `--ignore <id>` accepts one (an OSV id or a CVE), and `--json` prints them as JSON.
`JPM_OSV_URL` points it at another OSV server.

**SBOM.** `jpm sbom` writes a [CycloneDX](https://cyclonedx.org) 1.6 software bill of materials (JSON) for
the locked dependencies of every module: each package with its package URL, the SHA-256 from `project.lock`,
its license from its POM, and `"scope": "required"` when it ships at run time (`"excluded"` for dev and test
dependencies), and the dependency graph. Extensions are left out. It has no timestamp and its serial number
comes from its content, so the same lock gives the same file. `-o <file>` writes it to a file.

**Checksums.** Every file downloaded from a Maven repository is checked against the strongest checksum the
repository publishes next to it (`.sha512`, `.sha256` or `.sha1`); one that does not match is deleted and
the install fails. See [ADR 0017](docs/adr/0017-checksums.md).

For libraries published with Gradle Module Metadata, the metadata also says where a Kotlin Multiplatform
library's JVM classes are: `io.mockk:mockk` resolves to `io.mockk:mockk-jvm`, as in Gradle.

**Workspaces.** A root `project.jsonc` with `"workspace": ["api", "impl"]`. Modules depend on each
other with `"workspace:impl": "*"`, share one version (lockstep) and one lockfile. Modules inherit
`java`, `target`, `group`, `license`, `url`, `layout` and `kotlin` from the root unless they set them, the
root's `javacArgs`, `nativeArgs` and `testJvmArgs` come before their own, and the root's `exclude` adds to theirs; `repositories` are read from the root only. Outside projects can
depend only on `public` modules; an `internal` module pulled in by a public one goes on the runtime
classpath but never the compile classpath, and always comes from the same commit as the module that
pulls it in. Prefixed tags (`api/v1.2.0`) version a module on its own. A module with `"isolated": true` is
resolved in a graph of its own (its own section of `project.lock`), so its dependencies never change
the versions the other modules get: for linters, formatters and code generators. Nothing can depend
on an isolated module, and it cannot depend on workspace modules.

**Commands across a workspace.** `jpm build` and `jpm test` at the root work on every module (inside a
module, on that one); `jpm run -r <script>` runs a script in every module that has it, in build order,
and stops at the first that fails. `--filter <pattern>` (repeatable, like pnpm's) picks modules for all
three: a name or glob (`--filter app`, `--filter 'lib-*'`), a folder (`--filter './libs/*'`),
`<pattern>...` with the modules it depends on, `...<pattern>` with the modules that depend on it, and
`!<pattern>` to leave modules out (`jpm run -r --filter '!docs' lint`).

**Builds** call the selected JDK's `javac`, with `--release` only when it is newer than the module's
target (so an API added later is a compile error, not a runtime crash). On a JDK of the target version
`--release` changes nothing, and leaving it out lets `javacArgs` open the JDK's own modules, e.g.
`["--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED"]`, which javac refuses together
with `--release`. Per module, a hash of the classpath, define values, JDK and
flags decides whether to reuse output. Per file, changed sources are recompiled; when a class's public
signature changes, every source that refers to it is recompiled too, and a change to a compile-time
constant rebuilds the module (javac inlines constants without leaving a reference). Dev dependencies are
the annotation processor path. Jars are reproducible.

**Defines** generate `BuildConfig` (in the main class's package, or `defineClass`) with `NAME`,
`VERSION` and one typed constant per define. Values come from the consumer (for libraries), the env
variable, the literal value, then the default. Libraries may declare defines without a value; a missing
or mistyped value fails with the library, variable, type and the exact snippet to add.

**Env files** load like Bun: `.env`, then `.env.development` (`dev`, `test`, `run`) or
`.env.production` (`build`, `start`), then `.env.local`, which overrides the other files. Variables
already set in the process environment win over all files, so CI secrets are never shadowed.

**Patches.** `jpm patch <dep>` unpacks the Git checkout or the Maven sources jar into
`.cache/patch/<name>` as a Git repo; `jpm patch --commit <dep>` writes the diff to `patches/` and
records it in the manifest. Git dependencies are patched before they are built. For Maven jars only the
changed classes are recompiled and swapped in. If a patch no longer applies, the build fails. A patch
can also be an object, `{ "file": "patches/x.patch", "dependencies": { "net.kyori:adventure-api": "5.2.0" },
"release": 25 }`: `dependencies` are compile-only for the patched classes (resolved and locked with the
project, never on anyone's classpath), and `release` compiles them for a newer Java than the library's
own bytecode level. Modular jars are patched as their module (`--patch-module`), so a patch can change
`module-info.java` and use package-private members.

**Tests** run on the JUnit Platform console launcher matching the resolved platform version; any
platform engine works. `jpm test com.example.FooTest`, `jpm test 'com.example.*'` or
`jpm test 'com.example.FooTest#bar'` select tests; `--tag <expr>` and `--exclude-tag <expr>` select
by JUnit tag (`jpm test --exclude-tag live`). In a workspace, a class runs in the modules whose tests have
it; naming a class no module has is an error. `JPM_TEST_JVM_ARGS` adds options to the JVM running the
tests, for example a coverage agent (`-javaagent:jacocoagent.jar=destfile=jacoco.exec`).
`jpm test --reports <dir>` writes JUnit XML reports (`TEST-*.xml`) for CI to show, in `<dir>/<module>` in a
workspace. `jpm test --watch` runs the tests again whenever a source, test, resource or manifest
changes, until Ctrl+C.

**Hot reload.** `jpm dev` runs the app with a small agent of jpm's. When only the module's code changes, jpm
recompiles it and redefines the classes that changed in the running JVM, so the app keeps its state; a
compile error leaves it running the code it had. The JVM can only replace method bodies, so a change that adds
or removes members, or a change to anything else (resources, the manifest, env files, other modules),
restarts the app. On JetBrains Runtime (`"java": "jetbrains-21"`), jpm adds
`-XX:+AllowEnhancedClassRedefinition`, which takes structural changes too. `jpm dev --restart` always
restarts.

**Scripts** run with `jpm run <name>`. `.java` scripts run on the source launcher with the runtime
classpath. A script that needs libraries of its own lists them with it:
`"generate": { "run": "scripts/Generate.java", "dependencies": { "com.google.code.gson:gson": "2.11.0" } }`;
they are resolved on their own, come first on the script's classpath and never move the project's versions
(a script that is not a `.java` file gets them in `JPM_SCRIPT_CLASSPATH`). `.sh` scripts with `sh`; anything else as a shell command. Scripts and extensions get
`JPM_PROJECT_DIR`, `JPM_MODULE_DIR`, `JPM_NAME`, `JPM_VERSION`, `JPM_MANIFEST`, `JPM_CLASSES_DIR`,
`JPM_CLASSPATH`, `JPM_JAR` (after a build), `JPM_JAVA_HOME`, and `JPM_EXE` and `JPM_VERSION_OF_JPM` to
call back into the jpm running them; extensions also get `JPM_CONFIG`.
Hooks are `pre`/`post` + `build`/`test`/`start`, optionally with `:<profile>` (`build --release` is
profile `release`).

**Code generators** are scripts with `"generates": "sources"` (or `"resources"`), what Maven and Gradle do with
code-generating plugins. Before compiling, jpm runs each one into a folder of its own, `JPM_GENERATED`, and
adds that folder to the module's sources (or resources); it runs again only when a file under its `"inputs"`
or its own definition changed. For example ANTLR:

```jsonc
"scripts": {
  "parser": {
    "run": "java -cp $JPM_SCRIPT_CLASSPATH org.antlr.v4.Tool -o $JPM_GENERATED/app -package app src/grammar/Calc.g4",
    "dependencies": { "org.antlr:antlr4": "4.13.2" },
    "generates": "sources",
    "inputs": ["src/grammar"]
  }
}
```

## Java modules

A module with `src/module-info.java` (or `src/main/java/module-info.java` in the Maven layout) builds
and runs as a named module, with nothing to configure:

- **Compiling:** dependencies that are modules (they have a `module-info.class` or an
  `Automatic-Module-Name`), and any jar whose automatic module name your `module-info.java`
  `requires`, go on the module path. Everything else stays on the class path, which the module can
  read. `requires static` fits `dev` dependencies such as annotations. A named module is always compiled
  as a whole; the per-module cache still skips it when nothing changed.
- **Running:** `jpm start` and `jpm dev` start it with `-m <module>/<main>`, so `provides`, `uses`,
  exports and encapsulation behave as declared; resources in `src/assets/` are part of the module.
- Tests compile and run on the class path, so they can reach internal packages. `java -jar` and
  bundles also use the class path, where the JDK ignores `module-info`.

## Kotlin

A module with Kotlin sources names the Kotlin version, and depends on the standard library like on any library:

```jsonc
{
  "name": "my-app",
  "kotlin": "2.2.20",
  "runtime": { "org.jetbrains.kotlin:kotlin-stdlib": "2.2.20" },
  "test": { "org.jetbrains.kotlin:kotlin-test-junit5": "2.2.20" }
}
```

`.kt` files go beside the `.java` files in `src/` (and `test/`); in the Maven layout, `src/main/kotlin` and
`src/test/kotlin` are source folders too. kotlinc (`org.jetbrains.kotlin:kotlin-compiler-embeddable` of that
version, resolved on its own like any tool) compiles first, seeing the Java sources and the defines class, so
the two languages can use each other; javac then compiles the Java against its output. Tests see the module's
`internal` declarations. A module's Kotlin is compiled as a whole when a source, the classpath or a setting
changes. A module with Kotlin sources only gets its defines class as Kotlin (an `object` with `const val`s,
which Java code reads as static final fields), so it needs no Java at all.

Compiler plugins and flags:

```jsonc
"kotlin": {
  "version": "2.2.20",
  "plugins": ["allopen", "serialization", "com.example:my-plugin:1.0"],   // Kotlin's own by name
  "args": ["-Xjsr305=strict", "-P", "plugin:org.jetbrains.kotlin.allopen:annotation=my.Open", "-J-Xmx2g"]
}
```

`plugins` are Maven coordinates (`group:artifact`, with the Kotlin version, or `group:artifact:version`) or the
names of Kotlin's own: `assignment`, `sam-with-receiver`, `allopen`, `noarg`, `serialization`, `lombok`,
`power-assert`, `parcelize`, `atomicfu`, `js-plain-objects`, `compose`. `-J` entries in `args` go to the JVM
running kotlinc. Workspace modules inherit `kotlin` from the root.

## Groovy and Scala

`"groovy": "4.0.24"` or `"scala": "3.5.2"` (Scala 2.13 works too) compiles the module's `.groovy` or `.scala`
sources, in its source folders (and `src/main/groovy` or `src/main/scala`, with the test folders to match, in
the `maven` layout), before javac. groovyc compiles jointly with the Java sources, and the Scala compiler
reads them, so Java and the other language can use each other's classes. The compiler is downloaded from
Maven Central like any tool; the runtime library is an ordinary dependency, and jpm says which to add when
it is missing (`org.apache.groovy:groovy`, before Groovy 4 `org.codehaus.groovy:groovy`;
`org.scala-lang:scala3-library_3`, for 2.13 `org.scala-lang:scala-library`). Workspace modules inherit both
fields from the root.

```jsonc
{ "name": "app", "version": "1.0.0", "scala": "3.5.2", "runtime": { "org.scala-lang:scala3-library_3": "3.5.2" } }
```

## Gradle plugins

`gradle:api` is the Gradle API, as Gradle's `gradleApi()` gives it to plugins. Gradle publishes no API jar
for current versions, so jpm downloads the distribution of that Gradle version (`gradle-9.8.0-bin.zip` from
services.gradle.org, or `JPM_GRADLE_DISTRIBUTIONS`) once into `~/.jpm/gradle/9.8.0` and puts its jars on the
classpath: the API, the Kotlin DSL extensions, TestKit, `ProjectBuilder`, and what Gradle provides to plugins
(Groovy, the Kotlin standard library, SLF4J). The archive's SHA-256 is locked in `project.lock`.

```jsonc
{
  "name": "my-gradle-plugin",
  "dev":  { "gradle:api": "9.8.0" },   // compiled against, provided by Gradle at run time
  "test": { "gradle:api": "9.8.0" }    // ProjectBuilder and TestKit tests
}
```

For a module with `gradle:api`, jpm also does what Gradle's `java-gradle-plugin` does for tests: the test
classpath has `plugin-under-test-metadata.properties`, so TestKit's `GradleRunner.withPluginClasspath()` runs
the plugin under test, and the test JVM gets the `--add-opens` options Gradle gives its own test workers. TestKit
runs the builds on the downloaded distribution. Plugin descriptors (`META-INF/gradle-plugins/<id>.properties`)
are ordinary resources. Plugins written in Kotlin with the `kotlin-dsl` conventions use the `assignment` and
`sam-with-receiver` compiler plugins with Gradle's annotations:

```jsonc
"kotlin": {
  "version": "2.4.10",   // the Kotlin that Gradle 9.8 embeds
  "plugins": ["sam-with-receiver", "assignment"],
  "args": [
    "-jvm-default=no-compatibility", "-Xjsr305=strict", "-Xsam-conversions=class",
    "-P", "plugin:org.jetbrains.kotlin.samWithReceiver:annotation=org.gradle.api.HasImplicitReceiver",
    "-P", "plugin:org.jetbrains.kotlin.assignment:annotation=org.gradle.api.SupportsKotlinAssignmentOverloading"
  ]
},
"exclude": ["org.jetbrains.kotlin", "org.slf4j"]   // Gradle provides them: keep them out of the plugin's bundle
```

A shaded plugin jar is a flat bundle with `"relocate"` (see below). jpm-publish does not write Gradle plugin
marker artifacts or publish to the Gradle Plugin Portal.

## Checks

`jpm check` is the one command for CI: it runs every check jpm and the project's extensions know, then the
tests, and reports every problem before it exits, so failing tests never hide anything else.

| Check | What it reports |
|---|---|
| `manifest` | `project.jsonc` does not load (unknown fields, bad values; the file and line) |
| `dependencies` | the dependencies do not resolve, or `project.lock` is stale with `--frozen` |
| `paths` | a field names a file or folder that does not exist: `sources`, `resources`, `patches`, script and hook files |
| `license` | a module with a `"group"` (published) has no `"license"`, or one that is not an SPDX id (warning) |
| `compile` | the sources or tests do not compile |
| `unused-dependencies` | a declared dependency none of the module's classes refer to, read from the compiled classes (warning) |
| `duplicate-classes` | two entries of a runtime classpath have the same class (warning) |
| `<extension>:<check>` | the checks of the extensions the project declares (see below) |
| `tests` | failed tests, each at its line in the test source |

Errors fail `jpm check`; warnings do only with `--fail-on-warning`. `jpm check paths license` runs just those
checks; `--no-test` leaves out the tests, `--exclude-tag <tag>` some of them. `--sarif <file>` and
`--junit <file>` write the problems as SARIF (GitHub code scanning shows them as annotations) and as JUnit XML
(one test case per check):

```yaml
- run: jpm check --frozen --sarif check.sarif
- uses: github/codeql-action/upload-sarif@v4
  if: always()
  with: { sarif_file: check.sarif }
```

Only extensions declared in the project add checks, so CI and every developer run the same ones. An extension
lists them in its descriptor; jpm runs the extension with the check's `"run"` arguments (default
`check <id>`). A non-zero exit fails the check, and the extension can write its problems, with locations, to the
file named by `JPM_CHECK_PROBLEMS` as a JSON array of `{"severity": "error" | "warning", "message", "file",
"line", "hint"}` (paths relative to the project).

## Extensions

An extension is a program that adds a command: `jpm publish` runs the extension `publish`, whose artifact is
`jpm-publish`. A project declares the extensions it uses in its root `project.jsonc`, next to their settings:

```jsonc
"extensions": {
  "github:java-package-manager/jpm-publish": "^0.2",
  "publish": { "javadoc": true }
}
```

`jpm add github:java-package-manager/jpm-publish` writes the declaration: jpm sees that it is an extension from
the `"extension"` field in its manifest. `jpm install` resolves each declared extension in a graph of its own,
so its libraries never move the project's versions, and pins it in `project.lock` under `"extensions"`; CI
needs no other step to run `jpm publish`. `jpm remove publish` takes it out again.

Inside the project, a declared extension wins over a global one of the same name. `jpm add -g <dep>` installs an
extension for every project, as `~/.jpm/bin/jpm-<name>`; `jpm update -g` and `jpm remove -g` manage those.
Settings for an extension that the root does not declare give a warning naming the `jpm add` that fixes it.

An extension describes itself under `"extension"` in its own manifest: a `description`, its `commands` (by
what follows `jpm`) with their `options`, and the `checks` it adds to `jpm check`
([docs/protocol.md](docs/protocol.md#extensiondescriptor) has the format). IDEs get these through the
protocol (`extensions/list`), so they show an extension's commands without running it. To write one, start
from [jpm-extension-template](https://github.com/java-package-manager/jpm-extension-template).

jpm runs an extension in the folder it was called from, with `JPM_EXE` (the jpm to call back),
`JPM_PROJECT_DIR`, `JPM_MODULE_DIR`, `JPM_MANIFEST`, `JPM_NAME`, `JPM_VERSION` and `JPM_CONFIG` (the module's
manifest as JSON).

## Dependencies from extensions

An extension can provide dependencies that no repository has, because they are made on your machine: a Paper
server jar patched from Mojang's, code generated from a schema. `ext:<extension>/<name>` names one:

```jsonc
"dev": { "ext:paperweight/server": "26.3.build.257-stable" }   // from jpm-paperweight
```

To resolve it, jpm runs `jpm-<extension> resolve <name> <version>` (the extension installed with `jpm add -g`, or
`jpm-<extension>` on PATH). The extension prepares what it provides, logs to stderr, and prints one JSON object:

```jsonc
{
  "version": "26.3.build.257-stable",                // the exact version; jpm asks for it again later
  "jars": ["/home/me/.jpm/paperweight/26.3.build.257-stable/paper-server.jar"],
  "sources": "/home/me/.jpm/paperweight/.../paper-server-sources.jar",   // optional, for IDEs
  "dependencies": { "io.papermc.paper:paper-api": "26.3.build.257-stable", "g:a:classifier": "1.0" }
}
```

The jars go on the classpaths of the scope the dependency is in. The dependencies are Maven packages, resolved
with the rest of the graph from the project's repositories. `project.lock` keeps the version and the SHA-256
of the jars, so an extension that makes different bytes for a locked version fails the install. jpm passes
`JPM_HOME` (where the extension can cache), `JPM_EXE` and `JPM_OFFLINE`. Versions such as `latest` mean what the
extension says; once locked, the locked version is used until `jpm update`.

## Bundles (jar-in-jar isolation)

For a module without a `main`, such as a plugin that a server loads, or with `jpm build --flat`, the bundle
is one flat jar instead: the module's classes and resources with those of every runtime dependency merged
in, the first copy of a file winning except service files (`META-INF/services/*`), which are joined. `dev`
dependencies stay out, as the platform provides them. `"relocate": { "org.yaml.snakeyaml": "com.example.shaded.snakeyaml" }`
moves packages to new names in it, as Shadow does: class references, descriptors, string constants naming
classes or resources, multi-release entries and service files are rewritten (names computed at run time are
not).

For a module with a `main`, `jpm build --bundle` (or `"package": "bundle"`) writes `dist/<name>-<version>-bundle.jar`, which runs
with `java -jar`. Nothing is shaded: the app and every dependency stay untouched jars inside it.

- The app and its dependencies load in a **shared** class loader whose parent is the JDK.
- Each **internal** implementation module (an `internal` workspace module pulled in by a `public` one,
  locally or from Git) gets its own **isolated** loader. It checks its own jars first, holding the
  module and the dependencies only it uses. The API module's packages and the JDK always come from the
  shared loader, so types match on both sides.
- The context class loader sees every isolated module, so registries that look implementations up by
  name (`ServiceLoader`, `Class.forName(name, true, contextLoader)`) find them, while the registry
  classes themselves stay in the shared loader.
- jpm generates the launcher and class loaders, compiled for the module's target (Java 8 or newer).
  Nested jars are unpacked once into a cache directory named after the bundle's contents
  (`$JPM_BUNDLE_CACHE`, or the temp directory).

Isolation controls what each part of the program can see: the app cannot reach an implementation's
private dependencies, and an implementation's dependencies never leak onto the app's classpath. Versions
are still resolved once for the whole graph, so an isolated module cannot use a different version of a
package that the app also uses.

## Importing Maven and Gradle projects

Moving a project over? [MIGRATION.md](MIGRATION.md) walks through it step by step.

```sh
cd some-maven-or-gradle-project
jpm import --dry-run      # show the manifests it would write, and what does not carry over
jpm import                # write project.jsonc (one per module) and project.lock
jpm build && jpm test
```

Files stay where they are: imported manifests use `"layout": "maven"`. Multi-module builds become a
workspace, and module-to-module dependencies become `workspace:` dependencies.

- **Maven** is read from the effective model (parent POMs on disk or from Maven Central, properties,
  dependency management and BOMs). Scopes map as compile and runtime → `runtime`, provided → `dev`,
  test → `test`; annotation processor paths go to `dev`. Java versions, main classes (jar, exec, shade,
  assembly and Spring Boot plugins) and extra repositories carry over.
- **Gradle** is read by running Gradle (`gradlew`, else `gradle`) with an init script that reports the
  evaluated model, so plugins, conventions and version catalogs are taken into account. `--static`
  reads `settings.gradle(.kts)`, `build.gradle(.kts)`, `gradle.properties` and
  `gradle/libs.versions.toml` as text instead, executing nothing; this is also the fallback when Gradle
  is not installed. Versions managed by platforms (BOMs) are read from the BOM's POM.

Exclusions, classifiers and runtime-only dependencies carry over. Anything without a jpm equivalent is
listed as a warning: plugins that generate code, custom source directories. Kotlin projects get `"kotlin"`
with the version of the standard library the Kotlin plugin added.

**Git dependencies without a `project.jsonc`** are imported the same way when they have a `pom.xml` or
a Gradle build, then built from source like any Git dependency. jpm never runs a dependency's build
scripts: Gradle builds of dependencies are always read as text. Imports are cached per commit in
`~/.jpm/imported`.

## JDKs and GraalVM

`"java"` names the JDK: `21`, `21.0.5`, or vendor-version such as `temurin-17`, `zulu-21`, `corretto-21`,
`graalvm-21` (GraalVM Community), `oracle-graalvm-21`, `mandrel-21`. A plain version accepts any vendor
and installs Temurin if none is present. jpm uses an installed JDK when one matches (`JAVA_HOME` first)
and otherwise downloads one through the [Foojay Disco API](https://api.foojay.io), verifies its SHA-256
and unpacks it into `~/.jpm/jdks`. Installing a JDK changes nothing outside that folder: no JAVA_HOME
or shell profile edits. Set `JPM_NO_INSTALL` to turn automatic installs off.

**`java` follows your project.** The installer puts shims for `java`, `javac`, `jar`, `jshell`,
`native-image` and the other JDK tools into `~/.jpm/bin`, first on PATH. Each picks a JDK, then runs
that JDK's tool:

1. inside a jpm project, the project's `"java"`, the same JDK `jpm build` and `jpm start` use;
2. otherwise the default you chose with `jvm use`;
3. otherwise the `java` that was already on your PATH, unchanged.

**`jvm`** (the same binary under another name; `bin/jvm` for the jar build) manages that:

```sh
jvm use 21                    # default JDK outside projects (installed if missing)
jvm use --project 25          # set "java" in this project instead (same as `jpm java use 25`)
jvm current                   # which JDK `java` runs here, and why
jvm exec 17 -jar app.jar      # one command with another JDK; `java` is implied for -jar, .jar and options
eval "$(jvm env graalvm-21)"  # this shell only
jvm install temurin-17 · jvm list · jvm remove temurin-17 · jvm home 21 · jvm use --unset
```

`jvm setup` creates the shims and the PATH setup (the installer does this, and `jpm upgrade` refreshes
it; `JPM_NO_SHIMS=1` skips the shims) and `jvm setup --remove` takes the shims away. `JPM_JAVA=21` overrides the choice for one shell or command.

**Native builds.** `jpm build --native` builds the jar, then runs native-image from a GraalVM of the
project's Java version (any installed GraalVM-based JDK, else GraalVM Community, installed) and writes
`dist/<name>`. Extra flags go in `"nativeArgs"`. `jpm start --trace` runs the app under the tracing
agent and merges what it records into `src/assets/META-INF/native-image/<group>/<name>/`, which ships in the
jar and is picked up by native-image automatically. `jpm test --trace` does the same for a test run, so the
tests' coverage of reflection, resources and proxies goes into the configuration too; what JUnit itself does
and what is done to the test classes is filtered out, since the configuration is for the application.

## Environment variables

| Variable | |
|---|---|
| `JPM_HOME` | Where jpm keeps its cache, JDKs and shims (default `$HOME/.jpm`) |
| `JPM_OFFLINE`, `--offline` | Use only what is cached |
| `JPM_REPO_<HOST>_USERNAME`, `_PASSWORD`, `_TOKEN` | Credentials for a Maven repository that needs them, see **Repositories** under [Manifest](#manifest) |
| `JPM_OSV_URL` | The OSV server `jpm audit` asks (default `https://api.osv.dev`) |
| `JPM_SPDX_BASE` | Where `jpm license` downloads license texts (default `https://spdx.org/licenses`) |
| `JPM_VERBOSE`, `-v` | Show what jpm runs and downloads; `JPM_VERBOSE=2` or `-vv` shows trace messages too |
| `-q`, `--quiet` | Show only errors and warnings |
| `--color=auto\|always\|never`, `JPM_COLOR`, `NO_COLOR`, `FORCE_COLOR` | Colored output. The first that is set decides: `--color`, `JPM_COLOR` (`1`/`always`, `0`/`never`, `auto`), `NO_COLOR` (never), `FORCE_COLOR` (always, unless `0`); otherwise jpm colors when stderr is a terminal. Scripts and extensions get `JPM_COLOR` from jpm |
| `JPM_NO_INSTALL` | Never install JDKs automatically |
| `JPM_NO_UPDATE_CHECK` | `jpm version` does not look for a newer jpm |
| `JPM_JAVA` | JDK for the `java` shims in this shell, e.g. `21` |
| `JPM_JVM_ARGS` | JVM options for `jpm start` and `jpm dev` |
| `JPM_TEST_JVM_ARGS` | JVM options for the JVM running the tests (`jpm test`), after `testJvmArgs` |
| `JPM_BUNDLE_CACHE` | Where bundles unpack their nested jars (default: the temp directory) |
| `JPM_MAVEN_CENTRAL`, `JPM_FOOJAY_URL` | Mirrors for Maven Central and the JDK index |
| `HTTPS_PROXY`, `HTTP_PROXY`, `NO_PROXY` | Proxy for downloads (any case), unless the `https.proxyHost` / `http.proxyHost` system properties set one |
| `SSL_CERT_FILE`, `SSL_CERT_DIR` | Extra trusted certificates (PEM), e.g. a proxy's CA; without them the system bundle (`/etc/ssl/certs/ca-certificates.crt` and the like) is added to the built-in ones |
| `JPM_VERSION_OVERRIDE` | Every module's version, e.g. one CI computes for publishing |
| `JPM_GRADLE_DISTRIBUTIONS`, `JPM_GRADLE_VERSIONS` | Mirrors for Gradle distributions (`gradle:api`) and for the current Gradle version (`jpm outdated`) |
| `JPM_GIT_BASE_GITHUB` (`_GITLAB`, `_CODEBERG`) | Redirect a Git host, e.g. to a mirror or `file:///…` for tests |
| `JPM_VERSION`, `JPM_NO_MODIFY_PATH`, `JPM_NO_SHIMS` | Install scripts: version, skip the PATH change, skip the shims |
| `JPM_REPO`, `JPM_INSTALL_BASE` | Install scripts and `jpm upgrade`: another GitHub repository, or another download location |

## License

MIT, see [LICENSE](LICENSE).
