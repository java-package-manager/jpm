# jpm

One command for Java, in the spirit of Cargo, Bun and Go: dependencies, builds, running, tests and
releases, driven by one `project.jsonc`. jpm resolves from Maven repositories and Git, installs the JDK
your project asks for, builds native executables with GraalVM, and moves Maven and Gradle projects over
with `jpm import`. It is a single native binary with no dependencies.

```jsonc
{
  "name": "hello",
  "version": "0.1.0",
  "java": "21",
  "main": "hello.App",
  "runtime": { "com.google.guava:guava": "^33.4" },
  "test": { "org.junit.jupiter:junit-jupiter": "5.11.4" }
}
```

## Install

```sh
curl -fsSL https://github.com/java-package-manager/jpm/releases/latest/download/install.sh | sh      # Linux, macOS
irm https://github.com/java-package-manager/jpm/releases/latest/download/install.ps1 | iex           # Windows
```

This puts `jpm` and `jvm` into `~/.jpm/bin`, first on your PATH, with `java`, `javac` and the other JDK tools as
shims that run the JDK of the project you are in. Open a new terminal afterwards; `jpm upgrade` keeps jpm up to
date. [Installing jpm](docs/install.md) has the details and the options.

## Five minutes

```sh
mkdir hello && cd hello
jpm init                              # project.jsonc, src/hello/App.java, test/, .gitignore
jpm add com.google.guava:guava        # the latest release, written into project.jsonc
jpm start                             # build and run main (the JDK is installed if it is missing)
jpm test                              # JUnit 5 is there without asking
jpm build                             # dist/hello-0.1.0.jar
jpm build --native                    # dist/hello, a native executable
```

Sources go in `src/`, resources in `src/assets/`, tests in `test/`. `project.lock` pins every version and
checksum; commit it. Moving an existing project? `jpm import` reads its Maven or Gradle build, and
[MIGRATION.md](MIGRATION.md) walks through the rest.

## Everyday commands

| Command | |
|---|---|
| `jpm add <dep>`, `jpm remove <dep>` | Add a dependency (`--dev`, `--test`), or remove one |
| `jpm install` | Resolve and write `project.lock`; `--frozen` in CI fails when it is stale |
| `jpm update`, `jpm outdated` | Move dependencies within their ranges (`--latest` past them); show newer versions |
| `jpm build` | Build into `dist/` (`--release`, `--bundle`, `--native`) |
| `jpm start`, `jpm dev` | Run `main`; `dev` reloads code changes into the running app |
| `jpm test` | Run the tests (`--watch`, a class or `Class#method`, `--tag`) |
| `jpm check` | Everything CI should check, then the tests, with every problem reported |
| `jpm run <script>` | Run a script from `"scripts"` |
| `jpm why <dep>`, `jpm audit`, `jpm sbom` | Why a package is there; known vulnerabilities; a bill of materials |
| `jpm java install 21`, `jvm use 21` | Install a JDK; choose what `java` runs outside projects |
| `jpm doctor` | Check this machine's setup and say what to fix |

[Commands](docs/commands.md) lists all of them. Dependencies can be Maven packages (`group:artifact`), Git
repositories (`github:user/repo`) or other modules of a workspace, with versions in Maven's, Gradle's or npm's
syntax: see [Dependencies](docs/dependencies.md).

## Documentation

[docs/](docs/README.md) has the rest:

- [project.jsonc](docs/manifest.md), every field; [Dependencies](docs/dependencies.md); [Building](docs/building.md)
  (Java modules, Kotlin, Groovy, Scala, defines, patches, bundles, native builds);
  [Running and testing](docs/running-and-testing.md); [Workspaces](docs/workspaces.md)
- [jpm check](docs/checks.md) for CI; [Extensions](docs/extensions.md), such as
  [jpm-publish](https://github.com/java-package-manager/jpm-publish) and
  [jpm-changeset](https://github.com/java-package-manager/jpm-changeset); [JDKs and GraalVM](docs/jdks.md)
- [Editor support](docs/editor-support.md): a JSON Schema for every editor, and the
  [IntelliJ plugin](https://github.com/java-package-manager/jpm-intellij);
  [Environment variables](docs/environment.md)

Working on jpm: [CONTRIBUTING.md](CONTRIBUTING.md). What comes next: [ROADMAP.md](ROADMAP.md).

## License

MIT, see [LICENSE](LICENSE).
