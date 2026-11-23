# jpm documentation

- [Installing jpm](install.md): the install scripts, upgrading, prereleases, building from source
- [Commands](commands.md): every command, generated from jpm's command registry
- [project.jsonc](manifest.md): the manifest, with every field, generated from its schema
- [Dependencies](dependencies.md): keys and versions, repositories and credentials, resolution, Git
  dependencies, the lockfile, checksums, `jpm audit` and `jpm sbom`
- [Building](building.md): incremental builds, defines, env files, patches, Java modules, Kotlin, Groovy and
  Scala, Gradle plugins, bundles, native builds
- [Running and testing](running-and-testing.md): tests, hot reload, scripts and hooks, code generators
- [Workspaces](workspaces.md): several modules in one project, and commands across them
- [jpm check](checks.md): the one command for CI
- [Extensions](extensions.md): commands from other programs, such as `jpm publish`
- [JDKs and GraalVM](jdks.md): `"java"`, the `java` shims and `jvm`
- [Importing Maven and Gradle projects](importing.md), and [MIGRATION.md](../MIGRATION.md) for a step-by-step move
- [Editor support](editor-support.md): the JSON Schema, and the IntelliJ plugin
- [Environment variables](environment.md)

For tools and IDEs: [the jpm protocol](protocol.md). For contributors: [decisions](adr/README.md),
[supported Java versions](java-versions.md) and [CONTRIBUTING.md](../CONTRIBUTING.md).
