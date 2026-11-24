# Installing jpm

```sh
curl -fsSL https://github.com/java-package-manager/jpm/releases/latest/download/install.sh | sh      # Linux, macOS
irm https://github.com/java-package-manager/jpm/releases/latest/download/install.ps1 | iex           # Windows
```

The scripts:

- download the native binary for your platform and check it against the release's `SHA256SUMS`;
- put `jpm` and `jvm` into `~/.jpm/bin`;
- keep that folder first on your PATH: one marked line in your shell's startup file (`~/.zshrc`,
  `~/.bashrc` and on macOS `~/.bash_profile`, or `~/.profile`) sources `~/.jpm/env`, which puts it
  first and moves it back to the front before every prompt, so a JDK that something else prepends later
  cannot shadow the shims (fish gets `conf.d/jpm.fish`; Windows gets the folder in the user PATH, but
  the system PATH comes first there). `JPM_NO_MODIFY_PATH=1` skips this;
- set up tab completion for `jpm` in bash, zsh and fish: the scripts go into `~/.jpm/completions`, which
  `~/.jpm/env` loads in interactive bash and zsh and fish finds through `conf.d/jpm.fish`. `jpm upgrade`
  rewrites them, so new commands complete too. `JPM_NO_COMPLETIONS=1` skips this (and removes them on the
  next `jvm setup`); on Windows, add `jpm completions powershell | Out-String | Invoke-Expression` to your
  `$PROFILE`;
- create `java`, `javac`, ... shims there, so `java` runs your project's JDK (see
  [JDKs and GraalVM](jdks.md)). Outside projects nothing changes until you run `jvm use`.
  `JPM_NO_SHIMS=1` skips this.

Open a new terminal afterwards. `JPM_VERSION=0.3.0` installs that version instead of the latest.

**Upgrading:** `jpm upgrade` replaces jpm with the latest release (checksum-verified, in place) and
refreshes the shims. `jpm upgrade --check` only reports; `jpm upgrade 0.3.0` installs a given version.

**Prereleases** (canaries) are versions like `0.13.0-beta.0`, released from jpm-changeset's prerelease mode.
`jpm upgrade` and the install scripts skip them; `jpm upgrade --canary` (and `JPM_VERSION=canary` for the
install scripts) installs the newest prerelease or release, whichever is newer, and `jpm upgrade` from a
prerelease moves to the release once it is out. `jpm upgrade <version>` installs a given one.

`jpm version` says when a newer release is out, and on a prerelease also when a newer prerelease is (on
stderr, after the version, so scripts reading the version are unaffected).

## From source

jpm has no dependencies of its own. It builds itself with a JDK 21:

```sh
./bootstrap.sh            # compiles jpm with javac, then jpm builds itself into cli/dist/ and api/dist/
export PATH="$PWD/bin:$PATH"
bin/jpm build --native    # a native binary in cli/dist/ (jpm installs GraalVM if needed)
```

On Windows use `bin\jpm.cmd` for the jar build. See [CONTRIBUTING.md](../CONTRIBUTING.md) for working on jpm.
