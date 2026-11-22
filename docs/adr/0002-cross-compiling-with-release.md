# 0002. `--release` only when cross-compiling

Status: accepted

## Context

`javac --release N` compiles against the API of Java N, so a class from a newer Java is a compile error
rather than a crash at run time. On a JDK whose version is N it changes nothing (same API, same bytecode),
but javac refuses it together with options that open the JDK's own modules, such as
`--add-exports jdk.compiler/...` in `"javacArgs"`.

## Decision

- jpm passes `--release` only when the target is older than the JDK (`Compiler`). kotlinc gets
  `-Xjdk-release` under the same rule (`KotlinCompiler`).
- To use `"javacArgs"` that open JDK modules, build with a JDK of the target version.
- The bundle launcher is compiled for the module's target, and for at least Java 8 (`Builder`).

## Known problem

When javac still refuses `--release`, `Compiler` recognizes the error by its text
(`"not allowed with --release"`). The roadmap replaces this with a decision from the arguments before
compiling (Phase 3, "No manual reading of Java source").
