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
- A Git dependency is built with the consuming project's JDK, which its author cannot choose. When that
  JDK is newer than the dependency's target and its `"javacArgs"` are refused with `--release`, jpm builds
  it with a JDK of its target instead (`Builder.targetJdk`), and installs one if none is found.
- The bundle launcher is compiled for the module's target, and for at least Java 8 (`Builder`).

- javac refuses some options together with `--release`: `--add-exports`, `--add-reads` and `--patch-module`
  of a JDK module (`java.*`, `jdk.*`), `--system` and `--upgrade-module-path`. jpm finds them in
  `"javacArgs"` before compiling and says which JDK to build with (`Compiler.refusedWithRelease`); it does
  not read javac's error text.
