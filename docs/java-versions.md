# Supported Java versions

jpm builds and tests projects for every Java version that still has public updates from at least one
major vendor: the long-term versions and the current feature release. `JavaVersionsTest` builds and runs
a plain project and a module project for every target below with every JDK at hand, and the
`java versions` CI job does it with all of these JDKs.

| Target | Released | End of public updates | Notes |
|---|---|---|---|
| 8 | 2014-03 | Temurin 2030-12, Corretto 2030-12 (Oracle: extended support only) | No Java modules: a module project needs target 9 or newer. Built with `-source/-target` on JDK 8, `--release 8` on newer JDKs. |
| 11 | 2018-09 | Temurin 2027-10, Corretto 2032-01 | |
| 17 | 2021-09 | Temurin 2027-10, Corretto 2029-10 | |
| 21 | 2023-09 | Temurin 2029-12, Corretto 2030-10, Oracle 2028-09 | jpm itself is built with 21. |
| 25 | 2025-09 | Temurin 2031-09, Corretto 2032-10, Oracle 2030-09 | |
| 27 | 2026-09 | Temurin 2027-03, Corretto 2027-04, Oracle 2027-03 | Feature release: replaced by 28 in March 2027. |

Dates are each vendor's end of public updates, from [endoflife.date](https://endoflife.date/eclipse-temurin)
([Corretto](https://endoflife.date/amazon-corretto), [Oracle JDK](https://endoflife.date/oracle-jdk)),
read on 2026-11-22. This table is reviewed every jpm release.

- A version leaves the list when no major vendor ships public updates any more. That is a deliberate,
  announced change in the release notes, not a side effect.
- When a new feature release comes out, it replaces the previous feature release in the list,
  `JavaVersionsTest.TARGETS` and the CI job.
- jpm reads `module-info.java` with the project's JDK, so building a module project needs JDK 11 or
  newer (for any target from 9 on).
