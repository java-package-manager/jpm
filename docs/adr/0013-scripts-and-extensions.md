# 0013. Scripts, hooks and extensions

Status: accepted

## Decision

- A script may name another script, which is useful for hooks (`Scripts`).
- `jpm run <module>:<script>` runs another module's script from anywhere in the workspace (`Commands`).
- A `.java` script's classpath is its own `//DEPS` first, then the module's runtime classpath. The roadmap
  moves script dependencies into the manifest and removes `//DEPS` (Phase 3).
- Scripts, hooks and extensions get `JPM_EXE`, so they call back into the jpm that runs them, and
  `JPM_COLOR` and `JPM_VERBOSE`, so that jpm prints the same way.
- `jpm start --trace` merges into an earlier native-image trace, or writes a fresh one the first time.
