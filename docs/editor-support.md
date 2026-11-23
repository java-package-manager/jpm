# Editor support

`project.jsonc` has a JSON Schema, [`cli/src/assets/dev/jpm/project.schema.json`](../cli/src/assets/dev/jpm/project.schema.json),
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
[docs/protocol.md](protocol.md)); the [IntelliJ plugin](https://github.com/java-package-manager/jpm-intellij)
does, so there every `project.jsonc` is covered without the line. (`jpm schema` did this before jpm 0.12.0.) The schema also describes the settings
of the `publish` extension under `"extensions"`.
