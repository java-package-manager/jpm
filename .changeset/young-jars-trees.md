---
"jpm": minor
---

`jpm audit` reports the locked dependencies with known vulnerabilities from the OSV database (id and CVE, severity, the version that fixes it, and how the project gets the package), and exits 1 when there are any; `--ignore <id>` accepts one, `--json` prints them as JSON.
