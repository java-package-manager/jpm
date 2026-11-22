# 0008. Patching dependencies

Status: accepted

## Decision

- `jpm patch <dep>` unpacks the sources into a folder that is made its own Git repository root, so the
  paths in the patch resolve against it (`Patches`).
- `jpm patch --commit` writes only the patch's `"file"` when the entry has options, keeping the rest.
- Only the changed classes are recompiled, against the original jar, everything the jar was compiled
  against, and the patch's own compile-only dependencies, taken from the project's graph (`Builder`).
- A modular jar is patched as its module (`--patch-module`), so a changed `module-info.java` sees the
  module's packages and changed classes see their module's other classes, package-private ones
  included. The compiler places the dependencies from the patched `module-info`'s `requires`.
- Signature files (`META-INF/*.SF`, `.RSA`, `.DSA`, `.EC`) are dropped from patched jars: they would no
  longer match the patched classes (`Jars`).
