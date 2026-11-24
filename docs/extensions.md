# Extensions

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
`jpm update -g --check` only reports: each global extension with the newer release `jpm update -g` would install,
and a newer one outside its range, which `jpm update -g --latest <name>` moves to.
Settings for an extension that the root does not declare give a warning naming the `jpm add` that fixes it.

An extension describes itself under `"extension"` in its own manifest: a `description`, its `commands` (by
what follows `jpm`) with their `options`, and the `checks` it adds to `jpm check`
([docs/protocol.md](protocol.md#extensiondescriptor) has the format). IDEs get these through the
protocol (`extensions/list`), so they show an extension's commands without running it. To write one, start
from [jpm-extension-template](https://github.com/java-package-manager/jpm-extension-template).

An extension from Git is built from source with the JDK its own `"java"` names, installed if missing, so
it builds the same in every project, whatever the project's `"java"`; without `"java"` it is built with the
project's JDK. This lets an extension use `"javacArgs"` such as `--add-exports jdk.compiler/...`, which javac
refuses with the `--release` that building on a newer JDK would need.

jpm runs an extension in the folder it was called from, with `JPM_EXE` (the jpm to call back),
`JPM_PROJECT_DIR`, `JPM_MODULE_DIR`, `JPM_MANIFEST`, `JPM_NAME`, `JPM_VERSION` and `JPM_CONFIG` (the module's
manifest as JSON).

An extension can also provide dependencies: see [Dependencies from extensions](dependencies.md#dependencies-from-extensions).

## Known extensions

These extensions can be added by their name: `jpm add publish` declares
`github:java-package-manager/jpm-publish` (`jpm add publish@^0.2` with a version range, `jpm add -g format` for
every project). Running one that is not installed, such as `jpm format`, says how to add it. Any other
extension is added by its dependency.

<!-- known: generated from KnownExtensions, `JPM_UPDATE_EXPECTED=1 bin/jpm test dev.jpm.DocsTest` rewrites it -->

| Command | Extension | |
|---|---|---|
| `jpm changeset` | [github:java-package-manager/jpm-changeset](https://github.com/java-package-manager/jpm-changeset) | Record changes as changeset files, then bump versions, write changelogs and publish |
| `jpm format` | [github:java-package-manager/jpm-format](https://github.com/java-package-manager/jpm-format) | Format and lint Java code: an IntelliJ-style formatter, IntelliJ's inspections and custom rules |
| `jpm publish` | [github:java-package-manager/jpm-publish](https://github.com/java-package-manager/jpm-publish) | Publish jpm modules to Maven repositories and Maven Central |

<!-- /known -->
