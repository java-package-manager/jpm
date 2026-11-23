# jpm

## 0.13.0

### Minor Changes

- `jpm upgrade --canary` installs the newest prerelease (such as 0.13.0-beta.0 from jpm-changeset's prerelease mode) or release, whichever is newer; a jpm on a prerelease hears about newer prereleases. jpm no longer builds a canary of every commit.

### Patch Changes

- Git dependencies follow a repository that moved: jpm's mirrors in ~/.jpm/git fetch from the URL the dependency names now (or JPM_GIT_BASE_<HOST>), not the one they were first cloned from.
- Updated dependencies: jpm-api@0.13.0
