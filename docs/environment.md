# Environment variables

| Variable | |
|---|---|
| `JPM_HOME` | Where jpm keeps its cache, JDKs and shims (default `$HOME/.jpm`) |
| `JPM_OFFLINE`, `--offline` | Use only what is cached |
| `JPM_REPO_<HOST>_USERNAME`, `_PASSWORD`, `_TOKEN` | Credentials for a Maven repository that needs them, see [Repositories](dependencies.md#repositories) |
| `JPM_OSV_URL` | The OSV server `jpm audit` asks (default `https://api.osv.dev`) |
| `JPM_SPDX_BASE` | Where `jpm license` downloads license texts (default `https://spdx.org/licenses`) |
| `JPM_VERBOSE`, `-v` | Show what jpm runs and downloads; `JPM_VERBOSE=2` or `-vv` shows trace messages too |
| `-q`, `--quiet` | Show only errors and warnings |
| `--color=auto\|always\|never`, `JPM_COLOR`, `NO_COLOR`, `FORCE_COLOR` | Colored output. The first that is set decides: `--color`, `JPM_COLOR` (`1`/`always`, `0`/`never`, `auto`), `NO_COLOR` (never), `FORCE_COLOR` (always, unless `0`); otherwise jpm colors when stderr is a terminal. Scripts and extensions get `JPM_COLOR` from jpm |
| `JPM_NO_INSTALL` | Never install JDKs automatically |
| `JPM_NO_UPDATE_CHECK` | `jpm version` does not look for a newer jpm |
| `JPM_JAVA` | JDK for the `java` shims in this shell, e.g. `21` |
| `JPM_JVM_ARGS` | JVM options for `jpm start` and `jpm dev` |
| `JPM_TEST_JVM_ARGS` | JVM options for the JVM running the tests (`jpm test`), after `testJvmArgs` |
| `JPM_BUNDLE_CACHE` | Where bundles unpack their nested jars (default: the temp directory) |
| `JPM_MAVEN_CENTRAL`, `JPM_FOOJAY_URL` | Mirrors for Maven Central and the JDK index |
| `HTTPS_PROXY`, `HTTP_PROXY`, `NO_PROXY` | Proxy for downloads (any case), unless the `https.proxyHost` / `http.proxyHost` system properties set one |
| `SSL_CERT_FILE`, `SSL_CERT_DIR` | Extra trusted certificates (PEM), e.g. a proxy's CA; without them the system bundle (`/etc/ssl/certs/ca-certificates.crt` and the like) is added to the built-in ones |
| `JPM_VERSION_OVERRIDE` | Every module's version, e.g. one CI computes for publishing |
| `JPM_GRADLE_DISTRIBUTIONS`, `JPM_GRADLE_VERSIONS` | Mirrors for Gradle distributions (`gradle:api`) and for the current Gradle version (`jpm outdated`) |
| `JPM_GIT_BASE_GITHUB` (`_GITLAB`, `_CODEBERG`) | Redirect a Git host, e.g. to a mirror or `file:///…` for tests |
| `JPM_VERSION`, `JPM_NO_MODIFY_PATH`, `JPM_NO_SHIMS` | Install scripts: version, skip the PATH change, skip the shims |
| `JPM_REPO`, `JPM_INSTALL_BASE` | Install scripts and `jpm upgrade`: another GitHub repository, or another download location |
