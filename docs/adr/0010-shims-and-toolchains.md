# 0010. JDK shims, toolchains and self-upgrade

Status: accepted

## Decision

Shims (`Main`, `Shims`, `JavaCommands`):

- Started as `java`, `javac` and so on, the native binary is a shim that runs the right JDK's tool with
  the arguments untouched. Only the native binary can be one: on a JVM, argv[0] is the JVM's own `java`.
  `JPM_AS=shim:<tool>` simulates one, for tests and the jar build.
- The native image does not parse `-X`, `-XX` or `-D` arguments (`-H:-ParseRuntimeOptions`), so the shim
  passes them to the JDK.
- A child `java` must not think it is a shim: `JPM_AS` is removed from its environment.
- The shim chooses the project's `"java"`, then the default from `jvm use`, then the next tool on PATH,
  skipping jpm's own bin folder and any file that is the running binary. After 8 nested shims
  (`JPM_SHIM_DEPTH`) it stops with an error rather than start itself forever.
- `jvm exec` takes an optional JDK; without one it makes the same choice as the shim. `jvm exec 21 -jar
  app.jar`, `jvm exec 21 app.jar` and `jvm exec 21 -version` all mean `java`. It runs the JDK's own tool,
  whatever PATH says.
- Ctrl+C goes to the child from the terminal; the shim ignores it, so it does not die first and orphan
  the child.
- On Windows shims are hard links (no extra disk space). A shim is jpm's when it is the same file as
  `jpm.exe`, or larger than 10 MB (an older copy of it).

## Known problem

The 10 MB rule is a guess: any large file of a shim's name in jpm's bin folder counts as jpm's
(`Shims.isOurs`).

Shell setup (`ShellSetup`): macOS terminals start login shells, which read `.bash_profile` rather than
`.bashrc`, so both are set up there. Files that hold the old setup line are migrated, whatever the
current shell.

Home and toolchains (`Fs`, `Jdk`, `Toolchain`, `Foojay`, `JavaSpec`):

- `$HOME` as the shell sees it wins over Java's `user.home` (from the user database), so jpm and the
  installer agree on `~/.jpm` when `HOME` is overridden (`sudo -E`, containers, CI).
- In a spec such as `temurin-17`, the version is the trailing part that starts with a digit after the
  last `-`.
- jpm records the distribution of what it installs, at the top of the install folder, which may be a few
  levels above the JDK home (macOS bundles); otherwise it guesses from the vendor.
- For the same version, tar.gz is preferred: it keeps symlinks and permissions, zip does not. After
  unpacking a zip, launchers are made executable again. Removing a macOS JDK removes its whole bundle
  folder.

Self-upgrade (`Upgrade`):

- The newest canary is the commit to have, whatever its version says. A canary build is a commit after
  the release it is named for, so it is compared with that release.
- A running `.exe` cannot be overwritten, but it can be renamed out of the way; a leftover `.old` file
  still in use is removed by the next upgrade. The shims are refreshed afterwards, because hard links on
  Windows still point at the old file.
- A `VERSION` file that is not a version gives no notice.
