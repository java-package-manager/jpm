# jpm

## 0.16.0

### Minor Changes

- The installer and `jvm setup` now set up tab completion for jpm in bash, zsh and fish, and `jpm upgrade` keeps it current. `JPM_NO_COMPLETIONS=1` leaves it out.

## 0.15.0

### Minor Changes

- Protocol 1.2: `dependencies/resolve` takes Git dependency keys too when it is given the project's `dir`, and builds them, so tools can load a library from a Git repository (jpm-format's formatters from Git)
- Kotlin compiles per file: a change recompiles the file and the files that use its classes, instead of the module
- Known extensions: `jpm add publish`, `jpm add format` and `jpm add changeset` (also with `-g`, `@<version>` or `#<branch>`) add jpm's own extensions by name, and running one that is not installed says how to add it
- Signatures: with `"trustedKeys"` (key fingerprints and the packages each may sign) in the workspace root, `jpm install` checks the OpenPGP signature published next to every jar: a signed jar must be signed by a trusted key and match its signature
- `project.lock` records the repository each Maven package came from, and jpm looks there first, then on the same host, then in the other repositories, so it asks fewer repositories for every file; `jpm check` warns about a listed repository no locked package comes from
- Protocol 1.2: `dependencies/resolve` resolves Maven dependencies in a graph of their own, downloads them and returns their jars, for tools that load a library at run time (`JpmClient.resolve`)
- `jpm import` translates Spotless (`spotless-maven-plugin`, Gradle's `spotless { }`) into jpm-format's settings: the Java formatter, unused imports, import order and license header, and the steps for other files as `"files"`
- Protocol 1.2: `dependencies/search` finds Maven coordinates by name, from jpm's download cache and Maven Central's search (`JpmClient.search`), so editors can complete `group:artifact`
- A minimum release age: `"minimumReleaseAge"` in the workspace root makes jpm pick only versions at least that old, and `jpm check` fails on a younger locked version unless `"releaseAgeExceptions"` names it.

### Patch Changes

- `jpm check` warns about a `"filter"` glob that matches no resource, and fails on one that is not a glob.

## 0.14.0

### Minor Changes

- Protocol 1.1: `dependencies/versions` gives the versions the Maven repositories have of each dependency and the newest release, for editors to complete versions and show updates.
- Hot reload: `jpm dev` redefines the classes a code change touches in the running JVM instead of restarting it (method bodies on any JDK, structural changes too on JetBrains Runtime), and restarts when that is not possible; `jpm dev --restart` always restarts.
- `jpm doctor` checks the machine (PATH order, shims, shell setup, JDKs and the default one, jpm's home and caches, whether the Maven repositories answer) and says what to fix; it exits 1 on an error.
- Private Maven repositories: credentials come from `JPM_REPO_<HOST>_USERNAME` and `_PASSWORD`, or `_TOKEN`, sent only over HTTPS and never along a redirect to another host; a 401 or 403 names the variables to set.
- Groovy and Scala sources: `"groovy"` and `"scala"` set the compiler version for a module's .groovy and .scala sources, compiled before javac and together with the Java sources (src/main/groovy and src/main/scala in the maven layout).
- Code generators: a script with `"generates": "sources"` or `"resources"` runs before compiling into its own folder (`JPM_GENERATED`), again only when its `"inputs"` change, and that folder joins the module's sources or resources; scripts that are not .java files get their dependencies in `JPM_SCRIPT_CLASSPATH`.
- `jpm test --reports <dir>` writes JUnit XML reports for CI, one folder per module in a workspace.
- `jpm sbom` writes a CycloneDX 1.6 software bill of materials (JSON) for the locked dependencies: package URLs, SHA-256 hashes from project.lock, licenses from POMs, whether each package ships at run time, and the dependency graph; the same lock gives the same file. `-o <file>` writes it to a file.
- `jpm test --trace` runs the tests under the native-image tracing agent and merges what they use into the configuration `jpm build --native` reads, leaving out what JUnit and the test classes do.
- Workspace-wide commands: `jpm run -r <script>` runs a script in every workspace module that has it, in build order; `--filter <pattern>` (like pnpm's: names and globs, `./folders`, `pattern...` with dependencies, `...pattern` with dependents, `!pattern` to leave out) picks modules for `jpm run`, `jpm build` and `jpm test`.
- POM profiles: profiles activated by `jdk` (the project's "java"), `os`, `property`, `file` or `activeByDefault` add their properties, managed versions and dependencies when POMs are read; changing "java" resolves again.
- Licenses: `jpm license <SPDX id>` sets "license" and writes LICENSE from SPDX's text (`jpm init --license` too); the schema lists every SPDX id; `jpm import` takes the license from the POM or the license file; `jpm check` reports ids that are not on the SPDX list and a "license" that does not match the license file.
- `jpm install` resolves merge conflicts in project.lock: it reads both sides, starts from the higher version of each package and resolves again; `--frozen` fails on a lockfile with conflict markers.
- `jpm cache size` shows how much space each of jpm's caches uses; `jpm cache clean [cache...]` deletes them (all, or the ones named), keeping installed JDKs, global extensions and shims.
- Extensions from Git are built with the JDK their own "java" names, so an extension whose "javacArgs" open JDK modules (such as jpm-format) builds in projects on any JDK
- Checksums: every file downloaded from a Maven repository is checked against the checksum the repository publishes (.sha512, .sha256 or .sha1), and project.lock pins each package's POM too ("pom"), so a POM that changed under a locked version fails.
- `jpm test --watch` runs the tests again whenever a source, test, resource or manifest changes.
- `jpm audit` reports the locked dependencies with known vulnerabilities from the OSV database (id and CVE, severity, the version that fixes it, and how the project gets the package), and exits 1 when there are any; `--ignore <id>` accepts one, `--json` prints them as JSON.

### Patch Changes

- CI runs the end-to-end tests on Windows too, on the JVM and on the native binary.
- The documentation moved into docs/: a page per topic, with the command reference generated from the command registry and the manifest field reference from the schema.
- On Windows, jpm deletes read-only files when it cleans up a folder, such as the Git objects of a `jpm patch` checkout, so `jpm patch --commit` no longer fails there.
- The project.jsonc schema describes every field, so editors show documentation on hover for all of them.
- `jpm init` and `jpm import` add .cache/, dist/ and .env.local to an existing .gitignore (one an IDE created, say) instead of leaving it without them.
- `jpm audit` sends each package to OSV with its fields in a fixed order, so the same lock always gives the same request.
- The README is for users now: what jpm is, installing, five minutes with it, the everyday commands, and links into docs/.

## 0.13.0

### Minor Changes

- `jpm upgrade --canary` installs the newest prerelease (such as 0.13.0-beta.0 from jpm-changeset's prerelease mode) or release, whichever is newer; a jpm on a prerelease hears about newer prereleases. jpm no longer builds a canary of every commit.

### Patch Changes

- Git dependencies follow a repository that moved: jpm's mirrors in ~/.jpm/git fetch from the URL the dependency names now (or JPM_GIT_BASE_<HOST>), not the one they were first cloned from.
- Updated dependencies: jpm-api@0.13.0
