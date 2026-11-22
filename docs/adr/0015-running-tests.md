# 0015. Running tests

Status: accepted

## Decision

- Tests run on the JUnit Platform console launcher at the version of the resolved platform engine
  (`TestRunner`). JUnit Jupiter is added when the module has tests and declares no test framework.
- The console launcher and the platform launcher it was resolved with come first on the classpath: a test
  dependency may bring an older `junit-platform-launcher` (`kotlin-test-junit5` does), which the console
  cannot run on.
- Modules that depend on the Gradle API get the JVM options Gradle gives its own test workers, because the
  Gradle runtime (ProjectBuilder, in-process builds) reflects into the JDK.
- `--tag` and `--exclude-tag` pass JUnit tag expressions to the launcher; `JPM_TEST_JVM_ARGS` adds options
  to the test JVM after `"testJvmArgs"`.
- jpm's own tests: every test gets an empty jpm home (`dev.jpm.testing.JpmHome`), tests that need the
  network are tagged `live` and run in a separate CI job, and the end-to-end tests in `dev.jpm.e2e`
  compare transcripts in `fixtures/expected/`.
