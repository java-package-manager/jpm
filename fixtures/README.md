Projects that the end-to-end tests (`cli/test/dev/jpm/e2e/EndToEndTest.java`) and CI build with the `jpm`
binary. They are not part of jpm's own sources.

- `workspace-demo`: a workspace with a library module that depends on a Maven artifact, an app module
  with defines, tests and a script.
- `maven-demo`: a `pom.xml` for `jpm import`.
- `gradle-demo`: a two-project Gradle build (Kotlin DSL) with a version catalog, a Java toolchain, the
  `application` plugin, a project dependency, a platform (BOM) and Lombok.
- `bundle-demo`: a workspace with a public API, an internal implementation (with a dependency only it
  uses) and an app packaged as a jar-in-jar bundle, to check class loader isolation.
- `expected/`: what each end-to-end scenario prints, with paths, versions, hashes and timings replaced
  by placeholders such as `<project>` and `<jdk>`.

The end-to-end tests need no network: each one gets an empty jpm home and a local Maven repository with
stubs of the libraries the fixtures use, plus JUnit copied from the cache of the jpm running the tests.

- `bin/jpm test dev.jpm.e2e.EndToEndTest` runs them on jpm's classes; `JPM_E2E_BINARY=cli/dist/jpm` runs
  them on the native binary instead.
- A changed transcript is a change in behavior. When it is intended, `JPM_UPDATE_EXPECTED=1` rewrites
  the files in `expected/`; review the diff before committing it.
