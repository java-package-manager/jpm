# 0007. Git dependencies

Status: accepted

## Decision

- `github:`, `gitlab:` and `codeberg:` keys are cloned from their host. `JPM_GIT_BASE_<HOST>` (for example
  `JPM_GIT_BASE_GITHUB=file:///path/`) redirects a host, for tests and mirrors (`DepKey`).
- A spec is an exact tag name, else a branch name: `main` is `#main` when there is no tag `main`.
- A commit that carries a version tag for the module is that version. Other commits are
  `<version>+<commit>`.
- A pin in the project's own manifest wins; otherwise the highest pinned version does.
- `@<commit>` ties a module to the commit of a sibling from the same repository. It decides only when
  nothing else asks for the module; otherwise the sibling follows the other requests (`Resolver`).
- A Git package's manifests come from the repository, or from importing its Maven or Gradle build. Its
  compile classpath comes from this project's graph, so its versions agree with ours (`GitModule`,
  `Builder`).
