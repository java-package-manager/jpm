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
Settings for an extension that the root does not declare give a warning naming the `jpm add` that fixes it.

An extension describes itself under `"extension"` in its own manifest: a `description`, its `commands` (by
what follows `jpm`) with their `options`, and the `checks` it adds to `jpm check`
([docs/protocol.md](protocol.md#extensiondescriptor) has the format). IDEs get these through the
protocol (`extensions/list`), so they show an extension's commands without running it. To write one, start
from [jpm-extension-template](https://github.com/java-package-manager/jpm-extension-template).

jpm runs an extension in the folder it was called from, with `JPM_EXE` (the jpm to call back),
`JPM_PROJECT_DIR`, `JPM_MODULE_DIR`, `JPM_MANIFEST`, `JPM_NAME`, `JPM_VERSION` and `JPM_CONFIG` (the module's
manifest as JSON).

An extension can also provide dependencies: see [Dependencies from extensions](dependencies.md#dependencies-from-extensions).
