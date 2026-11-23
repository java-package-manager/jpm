---
"jpm": minor
---

Checksums: every file downloaded from a Maven repository is checked against the checksum the repository publishes (.sha512, .sha256 or .sha1), and project.lock pins each package's POM too ("pom"), so a POM that changed under a locked version fails.
