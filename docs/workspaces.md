# Workspaces

A workspace is a root `project.jsonc` with `"workspace": ["api", "impl"]`, the folders of its modules, each with
a `project.jsonc` of its own. Modules depend on each
other with `"workspace:impl": "*"`, share one version (lockstep) and one lockfile. Modules inherit
`java`, `target`, `group`, `license`, `url`, `layout`, `kotlin`, `groovy` and `scala` from the root unless they set them, the
root's `javacArgs`, `nativeArgs` and `testJvmArgs` come before their own, and the root's `exclude` adds to theirs; `repositories` are read from the root only. Outside projects can
depend only on `public` modules; an `internal` module pulled in by a public one goes on the runtime
classpath but never the compile classpath, and always comes from the same commit as the module that
pulls it in. Prefixed tags (`api/v1.2.0`) version a module on its own. A module with `"isolated": true` is
resolved in a graph of its own (its own section of `project.lock`), so its dependencies never change
the versions the other modules get: for linters, formatters and code generators. Nothing can depend
on an isolated module, and it cannot depend on workspace modules.

## Commands across a workspace

`jpm build` and `jpm test` at the root work on every module (inside a
module, on that one); `jpm run -r <script>` runs a script in every module that has it, in build order,
and stops at the first that fails. `--filter <pattern>` (repeatable, like pnpm's) picks modules for all
three: a name or glob (`--filter app`, `--filter 'lib-*'`), a folder (`--filter './libs/*'`),
`<pattern>...` with the modules it depends on, `...<pattern>` with the modules that depend on it, and
`!<pattern>` to leave modules out (`jpm run -r --filter '!docs' lint`).
