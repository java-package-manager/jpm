# 0006. One version order, three range syntaxes

Status: accepted

## Decision

Versions order like Maven's `ComparableVersion`, with these rules (`Version`):

- Items are numbers or qualifiers, qualifiers lower-cased. A number beats a qualifier.
- Build metadata (semver `+...`) takes no part in ordering.
- Zeros before a qualifier do not count (`1.0-rc1` = `1-rc1`), and trailing zeros and release qualifiers
  are trimmed (`1.0.0` = `1` = `1.0-final`).

Ranges are written in Maven, Gradle or npm syntax (`VersionSpecs`, `NpmRange`):

- npm: `>= 1.0` is `>=1.0`. `^` keeps the left-most non-zero part (`^1.2.3` <2, `^0.2.3` <0.3,
  `^0.0.3` <0.0.4). `~1.2.3` and `~1.2` allow patch changes, `~1` minor changes. `>1.2` means `>=1.3.0`
  and `>1` means `>=2.0.0`; nothing is `>*`. A pre-release matches only a comparator that names a
  pre-release of the same version.
- Gradle: `1.2!!` is exactly 1.2, `[1.0,2.0)!!1.5` is the range preferring 1.5, and `+` is the newest
  version of any kind, like `latest.integration`.
- With a single requirement, that requirement chooses by its own rules (`latest.integration` takes
  snapshots, `!!` its preferred version); several requirements choose the newest version all accept
  (`Resolver`).
