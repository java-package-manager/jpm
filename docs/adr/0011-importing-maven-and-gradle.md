# 0011. Importing Maven and Gradle builds

Status: accepted

## Decision

Maven (`MavenImport`): the reactor is the buildable modules, with intermediate aggregators flattened.
Modules in profiles that are active by default count too.

Gradle, by running it (`GradleImport`): every project runs jpm's `jpmModel` task from an init script,
without the configuration cache (it cannot hold what the task reads). The root comes first, then the
subprojects in path order, as Gradle lists them.

Gradle, as text (`jpm import --static`), reading the build files without running anything:

- `include("a", ":b")` and `include 'a'` are modules; `includeBuild(...)` is another build.
- `group` and `version` come from the project's own top-level assignments (`group = "verification"`
  inside a task's block is the task's), then from `allprojects {}` / `subprojects {}` of the root, then
  `gradle.properties`. What the root sets up for subprojects is not about the root itself. Java settings
  in the root's `subprojects {}` or `allprojects {}` apply to every module.
- Dependency blocks of `buildscript {}` are for the build itself and are skipped.
- Groovy calls without parentheses run to the end of the line. A configuration block after a call
  (`implementation(x) { exclude(group = "g", module = "m") }`) gives exclusions, and
  `variantOf(libs.x) { classifier("...") }` a classifier.
- In version catalogs, values spanning lines (multi-line arrays and inline tables) are joined first.

Both:

- Dynamic versions (`1.+`, `latest.release`, ranges) are jpm versions too, and stay dynamic. What Gradle
  resolved fills in versions that are missing (managed by a platform) or unreadable.
- The Kotlin plugin's version is that of the standard library it adds.
- `junit-platform-launcher` is dropped: jpm runs tests with its own launcher.
