# jpm check

`jpm check` is the one command for CI: it runs every check jpm and the project's extensions know, then the
tests, and reports every problem before it exits, so failing tests never hide anything else.

| Check | What it reports |
|---|---|
| `manifest` | `project.jsonc` does not load (unknown fields, bad values; the file and line) |
| `dependencies` | the dependencies do not resolve, or `project.lock` is stale with `--frozen` |
| `release-age` | a locked version younger than `"minimumReleaseAge"` that `"releaseAgeExceptions"` does not name; an exception no longer needed (warning). Only with either field |
| `paths` | a field names a file or folder that does not exist: `sources`, `resources`, `patches`, script and hook files |
| `license` | a module with a `"group"` (published) has no `"license"`, or one that is not an SPDX id (warning) |
| `compile` | the sources or tests do not compile |
| `unused-dependencies` | a declared dependency none of the module's classes refer to, read from the compiled classes (warning) |
| `duplicate-classes` | two entries of a runtime classpath have the same class (warning) |
| `<extension>:<check>` | the checks of the extensions the project declares (see below) |
| `tests` | failed tests, each at its line in the test source |

Errors fail `jpm check`; warnings do only with `--fail-on-warning`. `jpm check paths license` runs just those
checks; `--no-test` leaves out the tests, `--exclude-tag <tag>` some of them. `--sarif <file>` and
`--junit <file>` write the problems as SARIF (GitHub code scanning shows them as annotations) and as JUnit XML
(one test case per check):

```yaml
- run: jpm check --frozen --sarif check.sarif
- uses: github/codeql-action/upload-sarif@v4
  if: always()
  with: { sarif_file: check.sarif }
```

Only extensions declared in the project add checks, so CI and every developer run the same ones. An extension
lists them in its descriptor; jpm runs the extension with the check's `"run"` arguments (default
`check <id>`). A non-zero exit fails the check, and the extension can write its problems, with locations, to the
file named by `JPM_CHECK_PROBLEMS` as a JSON array of `{"severity": "error" | "warning", "message", "file",
"line", "hint"}` (paths relative to the project).
