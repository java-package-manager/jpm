# 0003. Incremental builds and stable outputs

Status: accepted

## Decision

- javac runs once over every source when the module has annotation processors or is a named module
  (`module-info.java`): both need all sources in one run. Otherwise only changed sources are compiled.
- After a compile, jpm maps each class file to the source that produced it. Sources that used a deleted
  class are recompiled with the batch, so they fail if they still use it.
- A failed incremental compile deletes the state file, so the next build starts from a clean state
  instead of a half-updated one.
- Java that uses Kotlin is recompiled when the Kotlin output changes; kotlinc's classes are synced into
  the same classes folder as javac's, so jars and classpaths need nothing else (`Builder`).
- Files jpm writes for tools (for example the classpath file) carry no timestamp, so they change only
  when their content does and do not trigger rebuilds downstream.
