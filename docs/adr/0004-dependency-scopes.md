# 0004. Dependency scopes and what they pass on

Status: accepted

## Decision

- `runtime` dependencies are on the compile and runtime classpaths and are passed on to consumers.
- `dev` dependencies (and annotation processors) go on a compile classpath whole, with their own
  compile-only dependencies (`compileOnlyApi`), which are needed as much as the rest (`Builder`).
- An `"api": true` dev dependency is compiled against by consumers too, and never run with: the platform
  provides it at run time (`Resolver`). jpm-publish writes these as `provided` with Gradle module
  metadata that says consumers compile against them; `provided` is otherwise not passed on.
- In a workspace, the implementation of a public module's API is used at run time only, never compiled
  against (`Resolver`).
- A bare version from a POM is a preference; a range in any syntax is a requirement (`Resolver`).
- The same package in two scopes: `runtime` covers `dev` and `test`, so those entries are ignored with a
  warning, unless the `test` entry has `"tests": true`, which brings the module's tests as well. `dev` and
  `test` may share a dependency (a compile-only dependency the tests run with), with the same version
  (`Manifest`). `jpm add` keeps that pair in step (`Commands`).
- Compile-only dependencies of patches are resolved and locked with the project, so their versions agree
  with it, but are on no module's classpath: only the patched classes compile against them.
- A Git package's dev dependencies are its compile classpath and are resolved like a tool, with their
  compile-only dependencies.
- Imports map Gradle configurations the same way: `implementation` plus `testImplementation` or
  `compileOnly` of one dependency is a single runtime entry; `compileOnlyApi` becomes an `"api"` dev
  dependency (`GradleImport`).
