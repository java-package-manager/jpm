# Commands

Every command jpm has, from its command registry (the same list `jpm help` prints). Any other command runs an
[extension](extensions.md).

<!-- commands: generated from the command registry, `JPM_UPDATE_EXPECTED=1 bin/jpm test dev.jpm.cli.impl.CommandsTest` rewrites it -->

## Project

| Command | |
|---|---|
| `jpm init [name]` | Create a project; `--license <SPDX id>` sets its license and writes `LICENSE` (as `jpm license` does) |
| `jpm add <dep>[@version]` | Add dependencies (`--dev`, `--test`, `-r <url>` to add a Maven repository). An extension (a Git project with `"extension"` in its manifest) is declared in the root's `"extensions"` instead; `-g` installs one globally. A known extension can be added by its name (`jpm add format`). Without a version: latest release (Maven) or `^latest-tag` (Git) |
| `jpm remove <dep>` | Remove dependencies, or a declared extension by name or key (`-g` removes a global extension) |
| `jpm install` | Resolve dependencies and declared extensions and write `project.lock`; `--frozen` fails if the lock is stale (CI) |
| `jpm update [dep...]` | Re-resolve within ranges; `--latest` rewrites the manifest past them. `update -g [name…]` updates global extensions |
| `jpm outdated` | Show dependencies with newer versions |
| `jpm why <dep>` | Show why a dependency is in the graph |
| `jpm info` | Describe the project: each module's version and folder, and its dependencies with what they resolve to. Programs use the jpm protocol instead (`jpm api`, [protocol.md](protocol.md)) |
| `jpm import [dir]` | Create `project.jsonc` from a Maven or Gradle build (`--dry-run`, `--static`, `--force`) |
| `jpm license [id]` | Show the project's license and license file; `license <SPDX id>` sets `"license"` and writes `LICENSE` from SPDX's text with the year and the author (`--author`, else git's `user.name`) filled in; `--force` replaces a license file |
| `jpm check [check...]` | Run jpm's checks (manifest, dependencies, release-age, paths, license, compile, unused-dependencies, duplicate-classes), the checks of the extensions the project declares, then the tests, and report every problem; `--no-test`, `--fail-on-warning`, `--exclude-tag <tag>`, `--sarif <file>`, `--junit <file>`, `--frozen` |

## Build and run

| Command | |
|---|---|
| `jpm build` | Build into `dist/` (`--release`, `--profile <name>`); in a workspace, every module, the one you are in, or the modules named; `--filter <pattern>` (repeatable) picks workspace modules: a name or glob (`app`, `lib-*`), a folder (`./libs/*`), `<pattern>...` with what it depends on, `...<pattern>` with what depends on it, `!<pattern>` leaves modules out |
| `jpm start [entry] [-- args]` | Build, then run `main` with the production env |
| `jpm dev [entry] [-- args]` | Run `main` with the development env: a change to the module's code is recompiled and reloaded into the running JVM (method bodies; any change on JetBrains Runtime), anything else, or a change that cannot be reloaded, restarts it; `--restart` always restarts |
| `jpm test [filter...]` | Run tests on the JUnit Platform: a class, `Class#method` or a pattern selects tests, `--tag` and `--exclude-tag` select by JUnit tag; `--reports <dir>` writes JUnit XML reports for CI (one folder per module in a workspace); in a workspace, `--filter <pattern>` (repeatable) picks workspace modules: a name or glob (`app`, `lib-*`), a folder (`./libs/*`), `<pattern>...` with what it depends on, `...<pattern>` with what depends on it, `!<pattern>` leaves modules out |
| `jpm run <script> [args]` | Run a script from `scripts` (no shorthand); `<module>:<script>` runs a workspace module's script from anywhere in the workspace |
| `jpm run -r <script> [args]` | Run a script in every workspace module that has it, in build order, stopping at the first that fails; `--filter <pattern>` (repeatable) picks workspace modules: a name or glob (`app`, `lib-*`), a folder (`./libs/*`), `<pattern>...` with what it depends on, `...<pattern>` with what depends on it, `!<pattern>` leaves modules out (and implies `-r`) |
| `jpm clean` | Delete build output |

## Dependencies

| Command | |
|---|---|
| `jpm audit` | Report the locked dependencies with known vulnerabilities, from the OSV database: id, severity, the version that fixes it and how the project gets it; exits 1 when there are any. `--ignore <id>` (repeatable) accepts one, `--json` prints them as JSON, `--frozen` |
| `jpm sbom` | Write a CycloneDX 1.6 software bill of materials (JSON) for the locked dependencies: package URLs, SHA-256 hashes, licenses and the dependency graph; the same lock gives the same file. `-o <file>` writes it to a file instead of stdout, `--frozen` |
| `jpm patch <dep>` | Unpack a dependency's source for editing |
| `jpm patch --commit <dep>` | Save your edits to `patches/` and record them in the manifest |

## Toolchain

| Command | |
|---|---|
| `jpm build --bundle` | Also build `dist/<name>-<version>-bundle.jar` with the runtime dependencies: a runnable jar-in-jar with isolated modules for a module with a `main`, one flat jar (like a shadow jar) for one without, such as a Paper or Velocity plugin; `--flat` makes it flat either way. `"package": "bundle"` makes it the default |
| `jpm build --native` | Also build a native executable of `main` with GraalVM into `dist/` |
| `jpm start --trace` | Run under the native-image tracing agent, recording configuration for native builds |
| `jpm java list [--available]` | Installed JDKs (`*` marks what `java` runs here), or what can be installed (`--available [vendor]`) |
| `jpm java install <java>` | Install a JDK or GraalVM into `~/.jpm/jdks` (`21`, `temurin-17`, `graalvm-21`, ...) |
| `jpm java remove <java>` | Remove a JDK jpm installed |
| `jpm java use <java>` | Set `"java"` in the manifest, installing it if needed |
| `jvm use <java>` | Choose the JDK that `java` runs outside projects (shims in ~/.jpm/bin) |
| `jvm current` | Which JDK `java` runs here, and why. `jpm java …` and `jvm …` are the same commands |
| `jvm exec [java] <cmd>` | Run one command with a JDK |

## Maintenance

| Command | |
|---|---|
| `jpm cache size` | Show how much space each of jpm's caches in `~/.jpm` uses (Maven packages, Git dependencies and their builds, patched jars, Gradle distributions, JDK downloads) |
| `jpm cache clean [cache...]` | Delete jpm's caches, or only the ones named (`repository`, `git`, `builds`, ...: see `jpm cache size`); what a project needs is downloaded or built again. Installed JDKs, global extensions and shims are kept |
| `jpm doctor` | Check this machine, not the project: PATH order, the shims, the shell setup, installed JDKs and the default one, jpm's home and caches, and whether the Maven repositories answer; prints what to fix and exits 1 on an error. `--offline` skips the network |
| `jpm upgrade [version]` | Upgrade jpm itself to the latest release (`--check` only reports, `--canary` also takes prereleases, such as `0.13.0-beta.0`) |
| `jpm version` | Show jpm's version (also `--version`), and whether a newer one is out |
| `jpm help` | Show this list of commands (also -h, --help) |
| `jpm completions <shell>` | Print shell completions. `jvm setup` (run by the installer and `jpm upgrade`) sets them up for bash, zsh and fish; elsewhere: `jpm completions powershell | Out-String | Invoke-Expression` in your profile |
| `jpm api` | Serve the jpm protocol (JSON-RPC on stdin and stdout, see [protocol.md](protocol.md)) for IDEs, extensions and tools; the client `dev.jpm:jpm-api` starts it |
| `jpm <anything else>` | Runs the extension `<name>`: the one the project declares, else `jpm-<name>` from `~/.jpm/bin` or `PATH` (see [Extensions](extensions.md)) |

<!-- /commands -->

## Arguments

Dependency arguments: `group:artifact@1.2.3`, `group:artifact:1.2.3`, `'group:artifact@[1.0,2.0)'`,
`github:user/repo@^1.2`, `github:user/repo#main`, `github:user/repo/module@a1b2c3d`, `gradle:api@9.8.0`
(see [Gradle plugins](building.md#gradle-plugins)), `ext:paperweight/server@26.3` (see [Dependencies from extensions](dependencies.md#dependencies-from-extensions)).

Global options, for every command: `-v`/`--verbose` (`-vv` for more), `-q`/`--quiet`, `--color=auto|always|never`
and `--offline`; see [Environment variables](environment.md).

Shell completions: the installer sets them up for bash, zsh and fish (`jvm setup` does it, and `jpm upgrade`
keeps them current); `jpm completions bash|zsh|fish|powershell` prints them for anything else.
