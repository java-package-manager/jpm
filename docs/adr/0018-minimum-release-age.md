# 0018. Minimum release age

Status: accepted

## Context

A compromised release is usually found and pulled from the repository within days. A project that only takes
versions some days old is unlikely to pick one up. Versions get into `project.lock` in several ways: `jpm update`
and `jpm add` pick them, a hand edit or a Renovate pull request writes them, a POM pins them. So picking old
enough versions is not enough. `jpm check` has to look at every locked version.

## Decision

- The workspace root's `project.jsonc` sets `"minimumReleaseAge"`: a number and a unit, such as `"7 days"`,
  `"7d"`, `"12 hours"` or `"2 weeks"` (`m`/`minutes`, `h`/`hours`, `d`/`days`, `w`/`weeks`). A module's
  manifest cannot set it, because the lock is shared by the whole workspace. Without it, nothing changes.
- **When jpm picks a version** (a range in the manifest or a POM, `jpm add` without a version, `jpm update`,
  `--latest`), it takes the highest one that is old enough. When no version that fits the range is old enough,
  it takes the one it would have taken anyway and warns, and `jpm check` fails until there is an exception or
  the version is old enough. An exact version is taken as written, because nothing else would satisfy it.
  A version that is already locked and still fits stays locked, as before.
- **`jpm check`** (the `release-age` check) reports an error for every locked Maven package and Git tag,
  transitive ones and the declared extensions' included, that is younger than the minimum. The only way past
  it is `"releaseAgeExceptions"` in the root manifest, which maps a package to the one version that may be
  younger, such as `{"com.example:lib": "1.4.2"}`. jpm never writes an exception: taking a young release is
  a decision someone makes by hand. The error's hint shows the exception to add.
- An exception that no longer does anything is a warning: the package is no longer locked, it is locked at
  another version, or the excepted version is old enough now.
- **The release time** of a Maven version is the `Last-Modified` time the repository sends for its POM (a
  `HEAD` request), and the file's modification time in a `file:` repository. jpm keeps it in the cache next
  to the POM (`.released`), since it does not change. A version whose time jpm cannot learn (the repository
  sends no `Last-Modified`) is a warning in `jpm check` and counts as old enough when picking. A Git tag's
  time is its tagger date (the commit date for a lightweight tag), from the mirror jpm already keeps.
- **Not checked:** snapshots, Git dependencies on a branch or a commit, workspace modules, Gradle's API, and
  JDKs. Asking for a snapshot or a branch is asking for whatever is newest; it has no release to wait for.
- `jpm check` compares against the time it runs. Versions only get older, so a check that passes keeps
  passing; at most an exception goes stale and becomes a warning.
