# Architecture decision records

Why jpm's code is the way it is, where that is not obvious from the code. jpm's sources have no comments
(see [0001](0001-decisions-live-in-adrs.md)): what a comment would explain goes here, or into Javadoc for
API types.

| ADR | Decision |
|---|---|
| [0001](0001-decisions-live-in-adrs.md) | Decisions live in ADRs, not in code comments |
| [0002](0002-cross-compiling-with-release.md) | `--release` only when cross-compiling |
| [0003](0003-incremental-builds.md) | Incremental builds and stable outputs |
| [0004](0004-dependency-scopes.md) | Dependency scopes and what they pass on |
| [0005](0005-maven-repositories.md) | Reading Maven repositories |
| [0006](0006-version-syntax.md) | One version order, three range syntaxes |
| [0007](0007-git-dependencies.md) | Git dependencies |
| [0008](0008-patching-dependencies.md) | Patching dependencies |
| [0009](0009-bundles-and-modules.md) | Bundles, relocation and Java modules |
| [0010](0010-shims-and-toolchains.md) | JDK shims, toolchains and self-upgrade |
| [0011](0011-importing-maven-and-gradle.md) | Importing Maven and Gradle builds |
| [0012](0012-network.md) | Network: certificates, proxies, retries |
| [0013](0013-scripts-and-extensions.md) | Scripts, hooks and extensions |
| [0014](0014-editing-jsonc-in-place.md) | Editing `project.jsonc` in place |
| [0015](0015-running-tests.md) | Running tests |
| [0016](0016-licenses.md) | Licenses |
| [0017](0017-checksums.md) | Checksums |
| [0018](0018-minimum-release-age.md) | Minimum release age |
| [0019](0019-signatures.md) | Signatures |

New ADRs take the next number. An ADR that is replaced stays, with its status set to "replaced by NNNN".
