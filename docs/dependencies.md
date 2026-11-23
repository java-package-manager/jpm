# Dependencies

Dependencies go in `"runtime"`, `"dev"` and `"test"`, keyed by what they are:

- `group:artifact`, or `group:artifact:classifier`: a Maven package
- `github:user/repo`, `gitlab:…`, `codeberg:…`, with `/module` for a module of a workspace: a Git repository
- `workspace:module`: another module of the same workspace
- `gradle:api`: the Gradle API (see [Gradle plugins](building.md#gradle-plugins))
- `ext:extension/name`: made by an extension (see [below](#dependencies-from-extensions))

## Versions

Versions can be written in Maven's, Gradle's or npm's syntax, for Maven and Git dependencies alike:

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

## Repositories

Maven Central is always searched. Other Maven repositories go in `"repositories"`
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

## Resolution

Maven and Git packages live in one graph and conflicts are settled the same way for
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

For libraries published with Gradle Module Metadata, the metadata also says where a Kotlin Multiplatform
library's JVM classes are: `io.mockk:mockk` resolves to `io.mockk:mockk-jvm`, as in Gradle.

## Git dependencies

Git dependencies go through the system `git` (so SSH keys and credential helpers work), into bare
mirrors under `~/.jpm/git`. Versions are ranges in any of the syntaxes above matched against tags,
exact tags, branches (`main`, or `#main` to say it is a branch; a tag of the same name wins over `main`) or
commits. A tag must match the version in that commit's manifest (when following a branch, a tag that does
not is ignored); untagged commits are versioned like `1.3.0+a1b2c3d`. The `"repositories"` a Git
dependency's manifest lists are searched too, after the project's own, for what it depends on. Git packages are built from source with the consuming project's resolved versions
and cached in `~/.jpm/builds`, keyed by commit, patch, define values, classpath and JDK. They are built with
the project's JDK; an extension is built with the JDK its own `"java"` names (see [Extensions](extensions.md)).
A repository
without a `project.jsonc` is imported from its `pom.xml` or Gradle build (see
[Importing](importing.md)); one with neither fails with a clear error.

Timestamped snapshot versions (`0.1-20240720.200737-2`) can be asked for by name, as in Gradle: they are read
from the folder of their `-SNAPSHOT` version.

## The lockfile

`project.lock` is sorted JSONC with one line per package: exact version, tag and commit
for Git, a SHA-256 for Maven jars (verified on every install) and for their POMs (`"pom"`, verified when
resolving), and its edges. It is reused as long as the declared dependencies are unchanged.

**Merge conflicts in `project.lock`.** Do not resolve them by hand: `jpm install` reads both sides of every
conflict, starts from the higher version of each package, resolves again and writes a clean lockfile.
`jpm install --frozen` fails on a lockfile with conflict markers, so CI never builds from one.

## Supply chain

**Checksums.** Every file downloaded from a Maven repository is checked against the strongest checksum the
repository publishes next to it (`.sha512`, `.sha256` or `.sha1`); one that does not match is deleted and
the install fails. See [ADR 0017](adr/0017-checksums.md).

**Minimum release age.** A compromised release is usually found and pulled within days, so a project can
wait that long before it takes a new version. In the workspace root:

```jsonc
"minimumReleaseAge": "7 days",
"releaseAgeExceptions": { "com.example:lib": "1.4.2" }
```

With it, jpm takes the highest version that is old enough whenever it picks one: `jpm add` without a
version, `jpm update`, `--latest`, and ranges in manifests and POMs. When no version that fits is old enough, it
takes the newest and warns. `jpm check` fails on every locked version that is younger than the minimum (Maven
versions and Git tags, transitive ones and extensions included), however it got into `project.lock`. The only
way past it is an exception for that package and version, added by hand; the error shows the one to add, and
`jpm check` warns about one that is no longer needed. A Maven version's age comes from the `Last-Modified`
time its repository sends for the POM, a Git tag's from its date. Snapshots and Git branches and commits are
not checked. See [ADR 0018](adr/0018-minimum-release-age.md).

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
