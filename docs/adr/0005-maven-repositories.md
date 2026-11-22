# 0005. Reading Maven repositories

Status: accepted

## Decision

- Maven Central is asked first. `JPM_MAVEN_CENTRAL` (or the `jpm.mavenCentral` property, for tests)
  replaces it.
- `file:` repositories are read in place: a file is copied when it is there.
- A "not found" is cached per set of repositories, so adding a repository looks again.
- Only a 404 means "not found". 401/403, 429 and 5xx are not proof that a file does not exist (some
  repositories answer 401/403 for files they do not have, proxies for hosts they block), so they are
  reported, never retried and never cached as missing. The next run asks again (`MavenRepo`).
- Snapshot repositories publish timestamped files (`lib-1.0-20250101.120000-3.jar`), named in that
  version's `maven-metadata.xml`: Maven 3 metadata lists one `<snapshotVersion>` per file, Maven 2 one
  timestamp and build number for all of them. `~/.m2` keeps the plain `-SNAPSHOT` name. A timestamped
  version asked for by name (as Gradle allows) lives in the folder of its `-SNAPSHOT` version.
- A classified jar (`g:a:classifier`) shares the POM of `g:a`.
- Dependency management: the parent's entries first, then the POM's own, then imported BOMs; the POM's
  own entries win (`Pom`).
- Gradle module metadata, when published, adds what a POM cannot say: compile-only dependencies that
  consumers compile against. Variants of other categories (documentation, platforms) are skipped, and so
  are variants for optional features (kotlin-test's JUnit support). A Kotlin Multiplatform library keeps
  its JVM classes in another module (`mockk` → `mockk-jvm`) that the POM may not depend on; jpm follows it
  like a relocation (`Resolver`, `GradleMetadata`).
