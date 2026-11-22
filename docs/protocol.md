# jpm protocol

Status: **accepted** (roadmap Milestone 4, item 1); `jpm api` serves protocol 1.0 with the methods in
`ApiServer.METHODS` (item 2). Section 8 records the decisions.

The protocol is how other programs (jpm-intellij, jpm-publish, extensions, editors) ask jpm about a project.
It replaces `jpm info --json`, `--ide`, `--classpath` and `jpm schema`, which jpm 0.12.0 removed. There is exactly one jpm on a machine, the global one; the client `dev.jpm:jpm-api` starts it
and talks to it. No program bundles its own jpm.

## 1. Transport

- `jpm api` serves the protocol on stdin and stdout until its client says `exit` or closes stdin.
- Messages are [JSON-RPC 2.0](https://www.jsonrpc.org/specification), framed as in LSP: a header
  `Content-Length: <bytes>\r\n\r\n`, then that many bytes of UTF-8 JSON. No other header is used.
- stdout carries protocol messages only. jpm's own messages go to the client as `log` notifications
  (section 4), and anything else jpm prints goes to stderr, which a client may show or ignore.
- One `jpm api` process serves one client and may answer for several projects: every request names the
  project's folder. Requests are answered in the order they arrive, except that `$/cancelRequest` and
  `exit` are handled at once.
- Paths are absolute and in the platform's own form (`C:\…` on Windows). Strings are UTF-8; JSON numbers
  that are counts or lines are integers.

## 2. Lifecycle and versions

```
client                                  jpm api
  ── initialize {protocolVersion: "1.0", client: {name, version}, logLevel?} ──▶
  ◀── {protocolVersion: "1.2", jpmVersion, methods: [...]} ─────────
  ── project/load {dir} ──▶ … any requests …
  ── shutdown ──▶   ◀── null
  ── exit (notification) ──▶   (process ends, exit code 0)
```

- `initialize` must come first. Any other request before it fails with `NotInitialized`.
- **Versions are `<major>` or `<major>.<minor>`** (`"1"` is `"1.0"`). A new major is incompatible: it may
  change or remove anything. A new minor is compatible: it only adds methods, optional params, result fields
  or notifications, or fixes bugs. A client must ignore result fields it does not know.
- jpm accepts a client whose major is the same as its own, whatever the minors. When the client's minor is
  higher than jpm's, the client may use features jpm does not have; it finds out from `methods` (and from the
  minor in the result) and must not call what is not listed.
- On a different major, `initialize` fails with `ProtocolMismatch`, and the error names both sides, for
  example: `jpm 0.12.0 speaks protocol 2.1, this client (jpm-intellij 0.8.0) speaks protocol 1.3: upgrade
  jpm-intellij`. The client jar turns this into an exception with the same text.
- jpm-publish and jpm-intellij keep working with a newer jpm of the same major, so they do not have to be
  released with every jpm release, only with every major.
- `methods` lists the methods this jpm serves, so a client can tell, for example, whether checks are
  available without calling them.

## 3. Methods

The first release has the methods below and nothing more (roadmap risk "Protocol scope creep"). Each
result type is defined in section 5.

| Method | Params | Result | Replaces |
|---|---|---|---|
| `initialize` | `protocolVersion` (string), `client`, `logLevel?` | `protocolVersion` (string), `jpmVersion`, `methods` | |
| `shutdown` | none | `null` | |
| `exit` (notification) | none | | |
| `project/load` | `dir`, `frozen?`, `sources?` | `Project` | `jpm info --json --ide` |
| `project/classpaths` | `dir`, `modules?` | `{modules: {name: Classpaths}}` | `jpm info --json --classpath` |
| `schema/manifest` | none | `{schema, version}` | `jpm schema`, `jpm schema --path` |
| `extensions/list` | `dir?` | `{extensions: [ExtensionDescriptor]}` | the plugin's built-in command list |
| `check/run` | `dir`, `checks?`, `tests?` | `{problems: [Problem], ran: [String]}` | |
| `$/cancelRequest` (notification) | `id` | | |

### `project/load`

Loads the project whose root or module folder is `dir` (or contains it), resolves its dependencies and
returns its model.

- Like `jpm install`, it writes `project.lock` when the lock is out of date, unless `frozen` is true, in
  which case an out-of-date lock is a `ProjectError`. The result's `lockfileChanged` says whether it wrote
  the lock, so a client that watches files can tell its own reload from a user's edit.
- `sources: true` downloads the sources jars of Maven dependencies and fills `Package.sources`; without it
  `sources` is set only where the jar is already cached. jpm-intellij asks for sources; jpm-publish does not.
- It does not build anything. Folders that a build fills (generated sources, classes) are reported even
  when they do not exist yet.

### `project/classpaths`

Every classpath of the named modules (all modules when `modules` is absent): `compile`, `processor`,
`runtime`, `testCompile` and `testRuntime`, as packages in classpath order with their jars and edges, and
the module path / class path split of a named module. Nothing is downloaded beyond what `project/load`
needs.

### `schema/manifest`

The JSON Schema of `project.jsonc` this jpm reads, as a JSON object, with `version` the jpm version. Editors
that want a file write it themselves; the protocol does not write `~/.jpm/schemas`.

### `extensions/list`

The commands and checks of every extension: the ones installed globally and, when `dir` is given, the ones
the project declares, which come first and take the place of global ones of the same name. jpm reads each
extension's descriptor from its manifest at the locked commit; it never runs an extension to list it. An
extension without a descriptor (a Maven jar, or a manifest that lists no commands) gets one command named
after it.

### `check/run`

Runs `jpm check` and returns its problems instead of printing them. `checks` limits it to some checks by id
(`paths`, `format:format`); `tests: false` leaves out the tests. Every check runs even when one fails, so
`problems` holds all of them and `ran` lists the checks that ran (one skipped because an earlier check failed
is not in it). Problem locations are absolute paths. What jpm prints while it runs (compiler and test output)
comes as `log` notifications.

## 4. Notifications from jpm

| Method | Params | When |
|---|---|---|
| `log` | `level` (`error`, `warn`, `info`, `debug`, `trace`), `message` | Every message jpm's logger would print, at the level `initialize` set (`logLevel`, default `info`). Colors are never used. |
| `progress` | `request` (the request id), `title`, `done?`, `total?` | Long steps: resolving, downloads, checks. `done`/`total` are bytes or counts when known. |

## 5. Types

Field names follow `jpm info --json` where it has one, so that moving to the protocol is mostly a change of
transport. A field marked `?` may be absent; a list is never absent, only empty.

### Project

| Field | Type | From `jpm info` |
|---|---|---|
| `dir` | path | `projectDir` |
| `name` | string | `name` |
| `version` | string? | `version` |
| `workspace` | boolean | `workspace` |
| `repositories` | [string] | `repositories` |
| `extensions` | object | `extensions` (the root's settings, by extension name, untouched) |
| `scripts` | [Script] | `scripts` (the root's) |
| `modules` | [Module] | `modules` |
| `lockfileChanged` | boolean | new |

### Module

| Field | Type | From `jpm info` |
|---|---|---|
| `name`, `group?`, `version` | string | same |
| `description?`, `license?`, `url?` | string | same (jpm-publish reads them) |
| `dir`, `relativeDir` | path, string | same |
| `visibility` | `public` or `internal` | same |
| `package` | `jar` or `bundle` | same |
| `layout` | `jpm`, `maven` | same |
| `main` | {name: class} | `main` |
| `folders` | Folders | `sourceDir(s)`, `resourceDir(s)`, `testDir(s)`, `testResourceDir`, `generatedSourceDir`, `excludeDirs` |
| `jar` | path | `jar` (where the build puts it) |
| `jdk` | Jdk | `java`, `javaHome`, `javaError`, `release` |
| `dependencies` | [Dependency] | `dependencies` (as declared) |
| `packages` | [Package] | `ideDependencies` (resolved, with the narrowest scope each is needed in) |
| `defines` | [Define] | `defines` |
| `scripts` | [Script] | `scripts` |
| `extensions` | object | `extensions` (the module's settings, merged over the root's by jpm-publish, not by jpm) |
| `modulePath?` | ModuleSplit | `compileModule` (a named module's split of the compile classpath and its javac options) |

### Folders

`sources`, `resources`, `tests`, `testResources`, `generatedSources`, `exclude`: each a list of paths.
`generatedSources` holds the folder of the defines class (Java or Kotlin, see ADR 0013 and the README).

### Jdk

`spec?` (the manifest's `"java"`), `home?` (the JDK jpm builds this module with), `release` (the bytecode
target), `error?` (why no JDK was found, for example offline with nothing installed). When `error` is set,
`home` is absent and the rest of the module is still reported.

### Dependency (as declared)

`key`, `kind` (`maven`, `git`, `workspace`, `gradle`, `extension`), `scope` (`runtime`, `dev`, `test`),
`spec`, `resolved?` (the locked version), `commit?`, `exclude` ([string]), `runtimeOnly`, `api`, `tests`
(booleans), and the parts of the key: `group?`, `artifact?`, `classifier?`, `module?` (a workspace module),
`host?` (a Git host).

### Package (resolved)

`id`, `kind`, `version?`, `commit?`, `scope` (`compile`, `provided`, `runtime`, `test`: the narrowest one
the package is needed in), `module?` (a workspace module's name), `jars` ([path]), `classes?` (a
workspace module's classes folder), `sources?` (path), `dependencies` ([id]).

### Classpaths

`compile`, `processor`, `runtime`, `testCompile`, `testRuntime`: each a list of `Package`, in classpath
order, without `scope` and `sources` (the classpath says where each is needed). `modulePath?`: the same as
in Module.

### ModuleSplit

`name` (the module's name, from javac's reading of `module-info.java`), `modulePath` ([path]),
`classPath` ([path]), `options` ([string]: the javac options jpm compiles the module with, as jpm-publish
needs them for javadoc).

### Define

`name`, `type` (`string`, `int`, `boolean`), `value` (as that type), `class` (the generated class's
name, the same for every define of a module).

### Script

`name`, `run` (the command or file), `kind` (`java`, `sh`, `command`), `dependencies` ({key: spec}).

### ExtensionDescriptor

`name` (`publish` for `jpm publish`), `key` (its dependency key), `version?` (absent before it is installed),
`source` (`global` or `project`), `description?`, `commands`: [{`name`, `description?`, `options`: [{`name`,
`argument?`, `description?`}]}], `checks`: [{`id`, `description?`}].

An extension describes itself in its own `project.jsonc`, under `"extension"`:

```jsonc
"extension": {
  "description": "Publish jpm projects to Maven repositories and Maven Central",
  "commands": {
    "publish": {
      "description": "Publish the modules to a Maven repository",
      "options": {
        "--dry-run": { "description": "Show what would be uploaded" },
        "--module": { "argument": "name", "description": "Publish only this module" }
      }
    }
  },
  "checks": { "pom": { "description": "The POM has what Maven Central requires", "run": ["--check-pom"] } }
}
```

`jpm check` runs a check as the extension with its `run` arguments (`check <id>` when there are none), with
`JPM_CHECK_PROBLEMS` naming a file for the problems it finds (a JSON array of `{severity, message, file?,
line?, hint?}`, paths relative to the project); a non-zero exit fails the check.

A command's name is what follows `jpm` and starts with the extension's name (`publish`, `format check`);
options start with `-`; check ids are lowercase letters, digits and `-`. jpm rejects a descriptor that
breaks these rules when it reads the manifest.

### Problem

The one shape for everything that is wrong: check results, and the `data` of every error.

| Field | Type | |
|---|---|---|
| `severity` | `error` or `warning` | |
| `message` | string | what is wrong |
| `location?` | {`file`: path, `line?`: integer (1-based), `column?`: integer (1-based)} | where |
| `hint?` | string | what to do next |
| `check?` | string | the check that found it, such as `jpm/unused-dependency` or `format/JavaFormat` |

This is the `JpmException` of today (message, location, hint) plus a severity and a check id. SARIF and
JUnit XML output of `jpm check` are made from the same records.

## 6. Errors

Errors are JSON-RPC errors. `data` is a `Problem` whenever jpm can say where and what to do.

| Code | Name | When |
|---|---|---|
| -32700, -32600, -32601, -32602 | JSON-RPC's own | unreadable JSON, not a request, unknown method, wrong params |
| -32001 | `ProtocolMismatch` | `initialize` with another major protocol version; `message` names both versions |
| -32002 | `NotInitialized` | a request before `initialize` |
| -32003 | `ProjectError` | what jpm reports as an error on the command line: a bad manifest, an out-of-date lock with `frozen`, a dependency that cannot be resolved; `data` is the Problem |
| -32800 | `Cancelled` | the client sent `$/cancelRequest` for it |

An unexpected failure inside jpm is `-32603` (internal error) with the exception's text, and the stack
trace as a `log` at level `debug`.

## 7. The client

The `api` module of jpm's repository, `dev.jpm.api`, holds the types above as records, the JSON reader and
writer (`dev.jpm.api.json.Jsonc`) and a client:

It is published as `dev.jpm:jpm-api` to `https://repo.thenextlvl.net/releases` with every jpm release:

```jsonc
"repositories": ["https://repo.thenextlvl.net/releases"],
"runtime": { "dev.jpm:jpm-api": "^0.12.0" }
```

```java
try (JpmClient jpm = JpmClient.start()) {            // finds the global jpm, starts `jpm api`, initializes
    Project p = jpm.load(Path.of("."), LoadOptions.sources());
    for (Module m : p.modules()) { ... }
}
```

- `JpmClient.start()` finds the global jpm: `JPM_EXE` when set, then `$JPM_HOME/bin/jpm`, `~/.jpm/bin/jpm`,
  then `jpm` on PATH. When there is none it throws `JpmNotInstalled`, which jpm-intellij answers by
  offering to install jpm (roadmap decision: never bundled).
- Each method is a blocking call; `log` and `progress` go to listeners the caller can register.
- The module has no dependencies and targets the Java of the oldest IDE jpm-intellij supports (Java 21
  today).

## 8. Decisions

Reviewed and accepted:

1. **One `jpm api` process per client**, not a shared daemon: jpm-intellij keeps one per IDE window,
   jpm-publish starts one per run. A daemon would bring locking and lifetime questions.
2. **No push notifications on change** in the first release: clients watch files and call `project/load`
   again; `lockfileChanged` tells a client's own reload from a user's edit.
3. **`<major>.<minor>` versions** (section 2): the same major is compatible, a minor only adds or fixes.
   This is the one exception to "no compatibility before 1.0": the protocol is what other programs build on.
4. **`schema/manifest` returns the schema itself**; jpm-intellij writes it where IntelliJ wants it.
5. **Names follow `jpm info`**, except `packages` (was `ideDependencies`), `folders` and `jdk`, which group
   fields.
