# 0017. Checksums

Status: accepted

## Decision

- Every file jpm downloads from a Maven repository (jars, POMs, Gradle module metadata) is checked against
  the strongest checksum the repository publishes next to it: `.sha512`, then `.sha256`, then `.sha1`
  (`MavenRepo.verified`). A file that does not match is deleted and the install fails; a repository that
  publishes none is trusted. Asking for the stronger ones first costs up to two extra requests per file,
  only when it is not cached yet.
- `project.lock` pins each Maven package's jar (`"sha256"`, checked on every install) and its own POM
  (`"pom"`, checked whenever resolution reads it): a locked version whose POM changed fails, `--frozen` or
  not, because the POM decides what else is resolved. Parent POMs and BOMs are checked against the
  repository's checksums when downloaded, but not pinned.
- A `-SNAPSHOT` version's files change with every build, so a lock that pins one fails once it moves on;
  `jpm update` re-pins it.
- Signatures (`.asc`) are checked only against a trusted key list: [ADR 0019](0019-signatures.md).
