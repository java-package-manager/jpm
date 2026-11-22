# 0013. Scripts, hooks and extensions

Status: accepted

## Decision

- A script may name another script, which is useful for hooks (`Scripts`).
- `jpm run <module>:<script>` runs another module's script from anywhere in the workspace (`Commands`).
- A `.java` script's classpath is its own `"dependencies"` first, then the module's runtime classpath. They
  are listed with the script in `project.jsonc` (`{"run": ..., "dependencies": {...}}`), not in the
  script: jpm does not read Java sources. They are resolved in a graph of their own and are not in
  `project.lock`, so a script's tools never change the project's versions. `//DEPS` lines are not read.
- Scripts, hooks and extensions get `JPM_EXE`, so they call back into the jpm that runs them, and
  `JPM_COLOR` and `JPM_VERBOSE`, so that jpm prints the same way.
- `jpm start --trace` merges into an earlier native-image trace, or writes a fresh one the first time.
