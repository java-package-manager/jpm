# Roadmap

What is still to come for jpm, in this order. An item is removed in the commit that finishes it; what has
shipped is in [CHANGELOG.md](cli/CHANGELOG.md) and [docs/](docs). jpm-format and jpm-intellij have roadmaps of
their own (*Other projects*).

## Windows

The end-to-end sessions run on Windows in CI, on the JVM and on the native binary (paths normalized to `/`).
Still skipped there:

- [ ] The Git and extension scenarios, which use `file://` Git bases.
- [ ] Scripts in sh syntax.
- [ ] Then the full test suite on Windows in CI, green, not only the native smoke test.

## Build

- [ ] Kotlin Multiplatform builds with targets other than the JVM.

## Errors and supply chain

- [ ] **Actionable errors:** every error says what went wrong, where (file and line for manifest
      errors), and what to do next; no stack traces unless `--verbose`.

## Tests

- [ ] Audit the test classes one by one: remove tests that only check implementation details, repeat other
      tests, or exist for a bug in code that no longer exists, and merge grab-bag tests into the tests of the
      feature they cover.

## Documentation

- [ ] A docs site built from `docs/` (optional, once `docs/` has been stable for a release).

## Other projects

- jpm-format: [ROADMAP.md](https://github.com/java-package-manager/jpm-format/blob/main/ROADMAP.md)
  (IntelliJ parity in formatting and lint, the native binary).
- jpm-intellij: [ROADMAP.md](https://github.com/java-package-manager/jpm-intellij/blob/main/ROADMAP.md).

## Last: Renovate

- [ ] A native Renovate manager for `project.jsonc` (Maven coordinates, `github:` dependencies, Gradle
      plugins, `"java"`) that keeps comments and formatting and updates `project.lock` with `jpm install`,
      contributed upstream, used on the jpm repositories, and documented as the supported way to keep jpm
      projects up to date. No regex-manager preset in between. It comes after everything above.
