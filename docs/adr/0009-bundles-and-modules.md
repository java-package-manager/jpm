# 0009. Bundles, relocation and Java modules

Status: accepted

## Decision

- `"package": "bundle"` builds a runnable jar-in-jar for a module with a main, and one flat jar for a
  module without one (plugins), or with `--flat` (`Commands`).
- In a jar-in-jar, an internal module gets its own class loader. Its API is the packages of the modules
  that pull it in, loaded from the shared loader (`Builder`).
- Running a named module resolves every module on the module path (`--add-modules`), so services they
  provide are found.
- The module path gets one jar per module name: two jars with the same module stop javac (`Modules`).
- A multi-release jar may keep `module-info.class` under a version folder only; jpm looks there too.
- Relocation (`"relocate"`) rewrites class names in the constant pool, in descriptors and signatures (a
  class name follows `L`, or `[` for arrays of internal names), in string constants that name classes,
  and in multi-release folders (`META-INF/versions/9/org/yaml/...` moves like `org/yaml/...`). A file
  that is not a class file jpm understands is left alone (`Relocator`).
- Unpacking skips entries that would land outside the target folder (zip slip) (`Jars`).

- A module's name and `requires` come from javac: before compiling, a small program on the project's JDK
  parses `module-info.java` with `com.sun.source` (cached by content); after compiling, jpm reads
  `module-info.class` (`Modules.declaration`, `Modules.compiled`). jpm reads no Java source itself.
