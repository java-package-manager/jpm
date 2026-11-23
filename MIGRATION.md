# Migrating from Maven or Gradle to jpm

This guide moves an existing Maven or Gradle project to jpm: what `jpm import` does for you, how each
concept maps, what does not carry over, and how to switch CI and publishing.

Most projects take three commands:

```sh
jpm import --dry-run    # see the manifests it would write, and what does not carry over
jpm import              # write project.jsonc (one per module) and project.lock
jpm build && jpm test
```

## Before you start

Install jpm (it adds `jpm`, `jvm` and the `java` shims to your PATH), or upgrade an existing install:

```sh
curl -fsSL https://github.com/java-package-manager/jpm/releases/latest/download/install.sh | sh      # Linux, macOS
irm https://github.com/java-package-manager/jpm/releases/latest/download/install.ps1 | iex           # Windows
jpm upgrade                                                                               # already installed
```

You do not need to install a JDK first: jpm installs the one the project asks for.

Work on a branch, and keep the Maven or Gradle files until the jpm build is green. jpm does not read them
after the import and does not change them.

## 1. Import

Run the import in the project's root directory, where `pom.xml` or `settings.gradle(.kts)` lives.

```sh
jpm import --dry-run
```

It prints each `project.jsonc` it would write, followed by warnings for whatever has no jpm equivalent.
Read the warnings first: they are your to-do list. Then:

```sh
jpm import             # --force overwrites existing project.jsonc files
```

This writes one `project.jsonc` per module, resolves the dependencies and writes `project.lock`.

**Maven** builds are read from the effective model: parent POMs (from disk via `relativePath`, else from
Maven Central or your repositories), properties, `dependencyManagement` and imported BOMs. Nothing is
executed.

**Gradle** builds are read by running Gradle (your `gradlew`, else `gradle` on PATH) with an init script
that reports the evaluated model, so plugins, conventions and version catalogs count. That runs your
build scripts, as any Gradle command would. `jpm import --static` reads the build files as text instead
and executes nothing; it is also what jpm falls back to when Gradle is not installed. Static mode
understands `settings.gradle(.kts)`, `build.gradle(.kts)`, `gradle.properties`, version catalogs
(`gradle/libs.versions.toml`) and platforms. It cannot see what plugins or `buildSrc` add, so check its
result more carefully.

### Your files stay where they are

Imported manifests contain `"layout": "maven"`, so the first build works without moving anything:
jpm reads `src/main/java`, `src/main/resources`, `src/test/java` and `src/test/resources`.

jpm's standard layout is the recommended one, and the one new projects use. Sources and tests each
have one folder, with their resources in `assets/` inside it:

```
src/              Java sources (was src/main/java)
src/assets/       resources, copied into the jar as is (was src/main/resources)
test/             test sources (was src/test/java)
test/assets/      test resources (was src/test/resources)
```

To switch, move the folders and drop the `layout` line. The sources are moved out of `src/` first,
because `src/main/java` cannot be moved into `src/` while it is still inside it:

```sh
git mv src/main/java .src-tmp              # temporary name, renamed to src/ below
git mv src/main/resources .assets-tmp      # if present
git mv src/test/java test                  # if present
git mv src/test/resources test/assets      # if present
find src -type d -empty -delete            # removes what is left of src/main and src/test
git mv .src-tmp src
git mv .assets-tmp src/assets              # if present
# then remove  "layout": "maven",  from project.jsonc
```

## 2. Build and test

```sh
jpm build      # compiles, packages dist/<name>-<version>.jar
jpm test       # runs on the JUnit Platform; any platform engine works
jpm start      # runs "main"
```

If something fails, see [Troubleshooting](#troubleshooting).

## 3. Review the manifest

`jpm import` writes plain versions for everything. That is deliberate: BOMs and dependency management are
resolved into the exact versions they stood for, so the build is reproducible without them. Open each
`project.jsonc` and tidy up what you want to keep flexible, e.g. Maven ranges (`[33.0,34.0)`). Run
`jpm install` after editing, and commit `project.jsonc` and `project.lock`.

## How Maven maps to jpm

| Maven | jpm |
|---|---|
| `pom.xml` | `project.jsonc` (JSON with comments) |
| `groupId`, `artifactId`, `version` | `group`, `name`, `version` |
| `description`, `url`, `licenses` | `description`, `url`, `license` (an SPDX id) |
| `<modules>` of an aggregator | a workspace: `"workspace": ["core", "app"]` on the root |
| module-to-module dependency | `"workspace:core": "*"` |
| parent POM | settings come over into each module; there is no inheritance afterwards |
| `<properties>` | used during the import; afterwards versions are written out |
| `dependencyManagement`, imported BOMs | resolved into exact versions on import |
| scope `compile` | `"runtime"` |
| scope `runtime` | `"runtime"` with `{ "version": "…", "runtimeOnly": true }` |
| scope `provided` | `"dev"` (on the compile classpath, never shipped) |
| scope `test` | `"test"` |
| `<optional>true</optional>` | a normal runtime dependency; review it |
| `<exclusions>` | `{ "version": "…", "exclude": ["group:artifact", "group:*"] }` |
| `<classifier>` | the third part of the key: `"group:artifact:classifier"` |
| `annotationProcessorPaths` | `"dev"` |
| `maven.compiler.release` / `source` / `target` | `"target"`; `"java"` names the JDK that builds it |
| `<repositories>` | `"repositories"` (Maven Central is always included) |
| main class (jar, exec, shade, assembly, Spring Boot plugins) | `"main"` |
| profiles | only modules of profiles active by default are imported |
| resource filtering (`${…}` in resources) | `"filter": ["app.properties"]`; see [`filter`](docs/manifest.md#filter) |

### Commands

| Maven | jpm |
|---|---|
| `mvn compile`, `mvn package` | `jpm build` |
| `mvn test` | `jpm test` (`jpm test 'com.example.*Test'` to filter) |
| `mvn exec:java`, `spring-boot:run` | `jpm start`, or `jpm dev` to restart on changes |
| `mvn dependency:tree` | `jpm why <dependency>`, `jpm info` |
| `mvn versions:display-dependency-updates` | `jpm outdated` |
| `mvn versions:use-latest-versions` | `jpm update --latest` |
| `mvn install` | `jpm publish --local` (with [jpm-publish](#publishing)) |
| `mvn deploy` | `jpm publish <repository>` |
| `mvn clean` | `jpm clean` |

## How Gradle maps to jpm

| Gradle | jpm |
|---|---|
| `settings.gradle(.kts)` with `include(...)` | a workspace on the root `project.jsonc` |
| `rootProject.name`, `group`, `version` | `name`, `group`, `version` |
| `api`, `implementation` | `"runtime"` |
| `runtimeOnly` | `"runtime"` with `"runtimeOnly": true` |
| `compileOnly`, `annotationProcessor` | `"dev"` |
| `compileOnlyApi` | `"dev"` with `"api": true` |
| `compileOnly` and `testImplementation` of one dependency | in both `"dev"` and `"test"` |
| `testImplementation`, `testCompileOnly`, `testRuntimeOnly` | `"test"` |
| `implementation(project(":core"))` | `"workspace:core": "*"` |
| `platform(...)`, `enforcedPlatform(...)` | resolved into exact versions on import |
| version catalog (`libs.…`) | resolved into exact versions on import |
| `exclude(group = …, module = …)` | `"exclude": ["group:module"]` |
| `configurations.all { exclude(…) }` | `"exclude"` on the module (or the workspace root) |
| `testFixtures`, a test-classes jar of another project | `"test": { "workspace:lib": { "version": "*", "tests": true } }` |
| `tasks.test { jvmArgs(…) }` | `"testJvmArgs"` |
| `java { toolchain { languageVersion = … } }` | `"java"` (installed automatically) and `"target"` |
| `application { mainClass = … }` | `"main"` |
| `repositories { maven { url = … } }` | `"repositories"` |
| tasks, `buildSrc`, convention plugins | not carried over; see [Custom build logic](#custom-build-logic) |
| `kotlin("jvm")`, `kotlin { compilerOptions { … } }` | `"kotlin": "<version>"`, or `{ "version", "plugins", "args" }`; see [Kotlin](docs/building.md#kotlin) |
| `java-gradle-plugin`, `kotlin-dsl`, `gradleApi()`, `gradleTestKit()` | `"gradle:api": "<Gradle version>"` in `"dev"` and `"test"`; see [Gradle plugins](docs/building.md#gradle-plugins) |
| `gradlePlugin { plugins { … } }` | a `META-INF/gradle-plugins/<id>.properties` resource per plugin |
| Shadow's `shadowJar` with `relocate(…)` | `jpm build --bundle` of a module without `main` (one flat jar) with `"relocate"` |

### Commands

| Gradle | jpm |
|---|---|
| `./gradlew build`, `assemble`, `jar` | `jpm build` |
| `./gradlew test` | `jpm test` |
| `./gradlew run` | `jpm start` (`jpm dev` restarts on changes) |
| `./gradlew dependencies`, `dependencyInsight` | `jpm info`, `jpm why <dependency>` |
| `./gradlew publishToMavenLocal` | `jpm publish --local` |
| `./gradlew publish` | `jpm publish <repository>` |
| `./gradlew shadowJar` | `jpm build --bundle` (a flat, relocated jar for a module without `main`; nested jars with one) |
| `./gradlew nativeCompile` | `jpm build --native` |
| `./gradlew clean` | `jpm clean` |

## What does not carry over

`jpm import` warns about each of these. Plan for them before removing the old build:

- **Code-generating plugins**: protobuf, OpenAPI, jOOQ, ANTLR and similar. Generate the sources with
  the tool directly from a script (see below), or commit the generated sources. Annotation processors
  (Lombok, MapStruct, Dagger, AutoValue) work as `dev` dependencies.
- **Groovy and Scala sources.** jpm builds Java and Kotlin.
- **Kotlin compiler plugins** that the Gradle plugins applied for you (`kotlin-dsl`, `kotlin("plugin.spring")`):
  list them in `"kotlin": { "plugins": [...] }` with their options in `"args"`.
- **Source templates** (blossom and similar): use defines, which generate a class of constants that Kotlin
  and Java read.
- **Gradle plugin publishing**: jpm-publish publishes Maven artifacts, but not plugin marker artifacts or to the
  Gradle Plugin Portal.
- **Custom source directories** (`sourceDirectory`, extra Gradle source sets). jpm uses one source,
  one resource and one test directory per module. Split extra source sets into their own workspace
  modules.
- **Resource filtering.** Use build-time defines: `"define": { "API_URL": { "type": "string", "env": "API_URL" } }`
  generates `BuildConfig.API_URL`.
- **Shading for a module with a `main`.** There `jpm build --bundle` gives a runnable jar-in-jar without
  relocating packages, and internal modules get their own class loaders; `--flat` makes one relocated jar.
- **Maven test jars** (`type test-jar`) of other projects. Within a workspace, `"tests": true` on a `test`
  dependency shares a module's test classes.
- **WAR/EAR packaging.** jpm builds plain jars.

### Custom build logic

Maven plugins and Gradle tasks become **scripts** and **hooks** in `project.jsonc`:

```jsonc
"scripts": {
  "generate": "scripts/Generate.java",        // a Java script, run with the project's classpath
  "lint": "scripts/lint.sh"
},
"hooks": {
  "prebuild": "generate",                     // runs before every jpm build
  "postbuild:release": "upload-mappings"      // only for jpm build --release
}
```

Run a script with `jpm run <name>`. Scripts get `JPM_PROJECT_DIR`, `JPM_VERSION`, `JPM_CLASSPATH`, `JPM_JAR`
and the other variables listed in [Scripts and hooks](docs/running-and-testing.md#scripts-and-hooks).

## Java modules

Projects with `module-info.java` need nothing extra. Dependencies that are modules go on the module path,
`requires static` works with `dev` dependencies, and `jpm start` runs the app as a module so `provides`
and `uses` behave as declared.

## JDKs

Instead of `JAVA_HOME`, the toolchain plugin, or `maven-toolchains-plugin`, set `"java"`:

```jsonc
"java": "21",          // or "temurin-21", "graalvm-21", "21.0.5"
"target": "17"         // bytecode level, defaults to java
```

jpm installs a missing JDK automatically, and inside the project your shell's `java` runs that JDK too.
`jvm current` shows which one is used and why.

## CI

Replace the Maven or Gradle setup with jpm. A GitHub Actions example:

```yaml
- uses: actions/checkout@v7
- name: Install jpm
  run: |
    curl -fsSL https://github.com/java-package-manager/jpm/releases/latest/download/install.sh | sh
    echo "$HOME/.jpm/bin" >> "$GITHUB_PATH"
- run: jpm install --frozen     # fails if project.lock does not match project.jsonc
- run: jpm test
- run: jpm build
```

No `setup-java` step is needed: jpm installs the project's JDK. Cache `~/.jpm/repository` and
`~/.jpm/jdks` between runs to save downloads.

## Publishing

Publishing to Maven repositories and Maven Central lives in the
[jpm-publish](https://github.com/java-package-manager/jpm-publish) extension:

```sh
jpm add -g github:java-package-manager/jpm-publish
```

Move what `distributionManagement`, `maven-deploy-plugin`, `maven-gpg-plugin` or the Gradle
`maven-publish` and `signing` plugins did into `project.jsonc`:

```jsonc
"description": "…", "license": "MIT", "url": "https://github.com/me/lib",
"extensions": {
  "publish": {
    "repositories": {
      "releases": { "url": "https://repo.example.com/releases", "snapshots": "https://repo.example.com/snapshots" },
      "central": { "type": "central" }
    },
    "developers": [ { "name": "Your Name" } ]
  }
}
```

Credentials move from `settings.xml` or `gradle.properties` to environment variables:
`JPM_PUBLISH_RELEASES_USERNAME` and `JPM_PUBLISH_RELEASES_PASSWORD` (the repository name upper-cased),
plus `JPM_SIGNING_KEY_FILE` and `JPM_SIGNING_PASSWORD` for signing. `jpm publish --dry-run` shows what
would be uploaded, and the published POM lists your runtime dependencies with the versions jpm resolved.

## Removing the old build

Once `jpm build`, `jpm test` and CI are green:

1. delete `pom.xml` files, or `build.gradle(.kts)`, `settings.gradle(.kts)`, `gradle.properties`,
   `gradle/`, `gradlew`, `gradlew.bat` and `buildSrc/`;
2. delete `target/` or `build/` output folders, and ignore jpm's instead: `.cache/` and `dist/`;
3. commit `project.jsonc`, `project.lock` and the new `.gitignore` entries.

In IntelliJ IDEA, the [jpm plugin](https://github.com/java-package-manager/jpm-intellij) opens the project from
`project.jsonc` (modules, dependencies with sources, the JDK) and can run the migration for you: it shows
what `jpm import` would write before writing it. With other IDEs, keep the old build files while the IDE
needs them to open the project, and update them by hand when dependencies change.

## Troubleshooting

**`module not found: …` while compiling.** The dependency is not a module and its automatic module name
does not match your `requires`. Check the name the jar declares (`Automatic-Module-Name` in its
manifest), or require the name derived from its file name.

**`Could not find group:artifact:version`.** It lives in a repository other than Maven Central. Add it:
`jpm add -r https://repo.example.com/releases group:artifact`, or list it in `"repositories"`.

**A dependency is missing at runtime.** It was `runtime`-scoped in Maven or `runtimeOnly` in Gradle and the
import was static, or it came from a plugin. Add it with `jpm add`.

**Tests are not found.** jpm runs tests on the JUnit Platform. JUnit 4 tests need the vintage engine:
`jpm add --test org.junit.vintage:junit-vintage-engine`.

**`java` in your shell runs the wrong JDK.** Run `jvm current`. If another directory comes before
`~/.jpm/bin` on PATH, `jvm setup` fixes your shell startup file; open a new terminal afterwards.

**The import warned about something not listed here.** Run `jpm import --dry-run` again after each
change; when the warnings are gone or understood, the migration is complete.
