# Importing Maven and Gradle projects

Moving a project over? [MIGRATION.md](../MIGRATION.md) walks through it step by step.

```sh
cd some-maven-or-gradle-project
jpm import --dry-run      # show the manifests it would write, and what does not carry over
jpm import                # write project.jsonc (one per module) and project.lock
jpm build && jpm test
```

Files stay where they are: imported manifests use `"layout": "maven"`. Multi-module builds become a
workspace, and module-to-module dependencies become `workspace:` dependencies.

- **Maven** is read from the effective model (parent POMs on disk or from Maven Central, properties,
  dependency management and BOMs). Scopes map as compile and runtime → `runtime`, provided → `dev`,
  test → `test`; annotation processor paths go to `dev`. Java versions, main classes (jar, exec, shade,
  assembly and Spring Boot plugins) and extra repositories carry over.
- **Gradle** is read by running Gradle (`gradlew`, else `gradle`) with an init script that reports the
  evaluated model, so plugins, conventions and version catalogs are taken into account. `--static`
  reads `settings.gradle(.kts)`, `build.gradle(.kts)`, `gradle.properties` and
  `gradle/libs.versions.toml` as text instead, executing nothing; this is also the fallback when Gradle
  is not installed. Versions managed by platforms (BOMs) are read from the BOM's POM.

Exclusions, classifiers and runtime-only dependencies carry over. Anything without a jpm equivalent is
listed as a warning: plugins that generate code, custom source directories. Kotlin projects get `"kotlin"`
with the version of the standard library the Kotlin plugin added.

**Git dependencies without a `project.jsonc`** are imported the same way when they have a `pom.xml` or
a Gradle build, then built from source like any Git dependency. jpm never runs a dependency's build
scripts: Gradle builds of dependencies are always read as text. Imports are cached per commit in
`~/.jpm/imported`.
