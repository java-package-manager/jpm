---
"jpm": minor
---

Workspace-wide commands: `jpm run -r <script>` runs a script in every workspace module that has it, in build order; `--filter <pattern>` (like pnpm's: names and globs, `./folders`, `pattern...` with dependencies, `...pattern` with dependents, `!pattern` to leave out) picks modules for `jpm run`, `jpm build` and `jpm test`.
