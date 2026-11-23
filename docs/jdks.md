# JDKs and GraalVM

`"java"` names the JDK: `21`, `21.0.5`, or vendor-version such as `temurin-17`, `zulu-21`, `corretto-21`,
`graalvm-21` (GraalVM Community), `oracle-graalvm-21`, `mandrel-21`. A plain version accepts any vendor
and installs Temurin if none is present. jpm uses an installed JDK when one matches (`JAVA_HOME` first)
and otherwise downloads one through the [Foojay Disco API](https://api.foojay.io), verifies its SHA-256
and unpacks it into `~/.jpm/jdks`. Installing a JDK changes nothing outside that folder: no JAVA_HOME
or shell profile edits. Set `JPM_NO_INSTALL` to turn automatic installs off.

**`java` follows your project.** The installer puts shims for `java`, `javac`, `jar`, `jshell`,
`native-image` and the other JDK tools into `~/.jpm/bin`, first on PATH. Each picks a JDK, then runs
that JDK's tool:

1. inside a jpm project, the project's `"java"`, the same JDK `jpm build` and `jpm start` use;
2. otherwise the default you chose with `jvm use`;
3. otherwise the `java` that was already on your PATH, unchanged.

**`jvm`** (the same binary under another name; `bin/jvm` for the jar build) manages that:

```sh
jvm use 21                    # default JDK outside projects (installed if missing)
jvm use --project 25          # set "java" in this project instead (same as `jpm java use 25`)
jvm current                   # which JDK `java` runs here, and why
jvm exec 17 -jar app.jar      # one command with another JDK; `java` is implied for -jar, .jar and options
eval "$(jvm env graalvm-21)"  # this shell only
jvm install temurin-17 · jvm list · jvm remove temurin-17 · jvm home 21 · jvm use --unset
```

`jvm setup` creates the shims and the PATH setup (the installer does this, and `jpm upgrade` refreshes
it; `JPM_NO_SHIMS=1` skips the shims) and `jvm setup --remove` takes the shims away. `JPM_JAVA=21` overrides the choice for one shell or command.

Native builds are described in [Building](building.md#native-builds).
