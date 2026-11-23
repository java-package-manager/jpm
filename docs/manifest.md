# project.jsonc

A project is a folder with a `project.jsonc`: JSON with comments and trailing commas. A workspace has one at its
root and one in each module (see [Workspaces](workspaces.md)).

```jsonc
{
  "name": "my-app",
  "version": "1.3.0",
  "java": "graalvm-21",   // installed automatically if missing
  "target": "17",         // bytecode target (--release), defaults to java
  "main": "com.example.App",          // or { "cli": "...", "server": "..." } → jpm start server
  "runtime": {
    "com.google.guava:guava": "[33.0,34.0)",
    "github:user/repo/api": "^1.2"
  },
  "dev":  { "org.projectlombok:lombok": "1.18.34" },
  "test": { "org.junit.jupiter:junit-jupiter": "5.11.0" },
  "define": {
    "API_URL": { "type": "string", "env": "API_URL" },
    "RETRIES": { "type": "int", "value": 3 },
    "DEBUG":   { "type": "boolean", "env": "DEBUG", "default": false },
    // values for defines declared by a dependency:
    "github:user/repo/api": { "API_KEY": "..." }
  },
  "scripts": { "format": "scripts/format.sh", "upload-mappings": "scripts/UploadMappings.java" },
  "hooks":   { "postbuild:release": "upload-mappings" },
  "patches": { "com.example:old-lib": "patches/old-lib.patch" }
}
```

Unknown fields are errors, and every error names the file and line.

Layout: `src/` with `src/assets/` (resources, copied into the jar as is), `test/` with `test/assets/` (test
resources), `scripts/`, `patches/`, `.cache/` (build cache, not committed), `dist/` (jars), `.changeset/` (reserved).

`src/assets/demo/icon.png` is `demo/icon.png` in the jar; service files go in
`src/assets/META-INF/services/`. `.java` files inside the assets folders are not compiled.

Dependencies, their versions and options are described in [Dependencies](dependencies.md).

## Every field

<!-- fields: generated from project.schema.json, `JPM_UPDATE_EXPECTED=1 bin/jpm test dev.jpm.DocsTest` rewrites it -->

### `name`

The project's name. It is the jar's name and, with "group", its Maven artifact id.

Type: a string.

### `version`

The project's version, e.g. 1.2.0 or 1.3.0-SNAPSHOT.

Type: a string.

### `group`

The Maven group id, needed to publish.

Type: a string. Workspace modules inherit it from the root.

### `description`

What the project is, for published POMs.

Type: a string.

### `license`

The license: an SPDX license id (SPDX license list 3.29.0, https://spdx.org/licenses/), or an SPDX expression such as "MIT OR Apache-2.0". Published POMs name it, and `jpm license <id>` writes the license file.

Type: one of 708 values, or a string. Workspace modules inherit it from the root.

### `url`

The project's home page, for published POMs.

Type: a string. Workspace modules inherit it from the root.

### `java`

The JDK the project builds and runs with, installed when it is missing: a major version, a distribution with a version, or an exact version. An extension's own "java" is the JDK it is built with in every project.

Workspace modules inherit it from the root.

### `target`

The bytecode level (javac --release). Defaults to the major version of "java".

Workspace modules inherit it from the root.

### `main`

The class `jpm start` runs, or several named entry points (`jpm start <name>`).

Type: a string, or an object.

### `runtime`

Dependencies needed to compile and run: on the compile classpath and shipped with the project.

Type: an object.

- each entry is a string, or an object; an object has:
  - `version`: a version or range, see [Versions](dependencies.md#versions)
  - `exclude` (a list of strings): Transitive dependencies to leave out: group:artifact, group:* or group.
  - `runtimeOnly` (`true` or `false`): Only on the runtime classpath, not the compile classpath (Maven's runtime scope).

### `dev`

Compile-time-only dependencies, never shipped: annotation processors, compile-only APIs (Maven's provided).

Type: an object.

- each entry is a string, or an object; an object has:
  - `version`: a version or range, see [Versions](dependencies.md#versions)
  - `exclude` (a list of strings): Transitive dependencies to leave out: group:artifact, group:* or group.
  - `api` (`true` or `false`): Pass this dependency on to consumers' compile classpaths (Gradle's compileOnlyApi): modules and projects that depend on this one compile against it too. It is never on a runtime classpath or in a bundle; the platform provides it.

### `test`

Dependencies for the tests only.

Type: an object.

- each entry is a string, or an object; an object has:
  - `version`: a version or range, see [Versions](dependencies.md#versions)
  - `exclude` (a list of strings): Transitive dependencies to leave out: group:artifact, group:* or group.
  - `tests` (`true` or `false`): For a workspace:<module> dependency: its compiled tests too (Gradle's test fixtures), so these tests can use its test base classes and helpers. Its tests are not run here.

### `define`

Build-time constants, generated as a class (BuildConfig) next to the main class. Keys with a colon give values for the defines a dependency declares.

Type: an object.

### `defineClass`

The fully qualified name of the generated defines class. Defaults to BuildConfig in the main class's package.

Type: a string.

### `scripts`

Commands run with `jpm run <name>`: a .java file (run with the project's classpath), a .sh file, or any shell command. An object gives a script dependencies of its own, resolved apart from the project's, or makes it a code generator ("generates").

Type: an object.

- each entry is a string, or an object; an object has:
  - `run` (a string): The .java file, .sh file or shell command.
  - `dependencies` (an object): Maven dependencies of a .java script, on its classpath before the module's; other scripts get them in JPM_SCRIPT_CLASSPATH. They are resolved on their own and never change the project's versions.
  - `generates` (one of `"sources"`, `"resources"`): Makes the script a code generator: before compiling, jpm runs it into a folder of its own (JPM_GENERATED) when its inputs or its definition changed, and adds that folder to the module's sources or resources.
  - `inputs` (a list of strings): Files and folders, relative to the module, whose changes make a generator run again.

### `hooks`

Scripts to run before or after build, test, install or start; ":<profile>" limits a hook to a profile (`jpm build --release` is profile release).

Type: an object.

### `patches`

Patch files applied to the sources of a dependency, which is then rebuilt: dependency key to the path of a .patch file, or to an object with the file and options.

Type: an object.

- each entry is a string, or an object; an object has:
  - `file` (a string): The .patch file.
  - `dependencies` (an object): Compile-only dependencies of the patched classes, resolved with the project; never added to anyone's runtime classpath.
  - `release` (a whole number): Java version to compile the patched classes for; default: the library's own bytecode level. Not lower than it.

### `workspace`

The folders of the workspace's modules, each with its own project.jsonc. A workspace root lists modules only.

Type: a list of strings.

### `isolated`

A workspace module resolved in a graph of its own (its own section of project.lock): its dependencies never change the versions other modules get. Meant for tooling and code generators; no module can depend on it, and it cannot depend on workspace modules.

Type: `true` or `false`.

### `sources`

Source folders of the module, relative to it, replacing the layout's: e.g. ["src/main/java", "src/generated/java"].

Type: a list of strings.

### `resources`

Resource folders of the module, relative to it, replacing the layout's. Earlier folders win when two hold the same file.

Type: a list of strings.

### `visibility`

public modules are published; internal modules are not, and bundles isolate them in their own class loader.

Type: one of `"public"`, `"internal"`.

### `repositories`

Maven repositories to search besides Maven Central (https:// or file:// URLs).

Type: a list of strings.

### `minimumReleaseAge`

How old a version must be before jpm picks it, such as "7 days": `jpm add`, `jpm update` and ranges take the highest version that is old enough, and `jpm check` fails on a locked version that is younger, unless `releaseAgeExceptions` names it. Snapshots and Git branches are not checked. Only in the workspace root.

Type: a string.

### `releaseAgeExceptions`

Packages that may be younger than `minimumReleaseAge`, each with the one version that may, such as {"com.example:lib": "1.4.2"} to take a security fix at once. jpm never adds one; `jpm check` shows the one to add, and warns about one that is no longer needed. Only in the workspace root.

Type: an object.

### `javacArgs`

Extra arguments for javac.

Type: a list of strings. Workspace modules inherit it from the root.

### `nativeArgs`

Extra arguments for GraalVM native-image (`jpm build --native`).

Type: a list of strings. Workspace modules inherit it from the root.

### `testJvmArgs`

Options for the JVM running the tests, e.g. ["-Xmx2g", "--add-opens=java.base/java.lang=ALL-UNNAMED"]. A workspace module's come after the root's.

Type: a list of strings. Workspace modules inherit it from the root.

### `groovy`

The module has Groovy sources (.groovy, in the source folders; src/main/groovy and src/test/groovy in the maven layout): groovyc of this version compiles them before javac, jointly with the Java sources. The runtime library (org.apache.groovy:groovy for Groovy 4 and later, org.codehaus.groovy:groovy before) is an ordinary dependency. Workspace modules inherit it from the root.

Type: a string. Workspace modules inherit it from the root.

### `scala`

The module has Scala sources (.scala, in the source folders; src/main/scala and src/test/scala in the maven layout): the Scala compiler of this version (2.13 or 3) compiles them before javac, seeing the Java sources. The runtime library (org.scala-lang:scala3-library_3 for Scala 3, org.scala-lang:scala-library for 2.13) is an ordinary dependency. Workspace modules inherit it from the root.

Type: a string. Workspace modules inherit it from the root.

### `kotlin`

The module has Kotlin sources: kotlinc of this version compiles them (before javac, seeing the Java sources). In the maven layout, src/main/kotlin and src/test/kotlin are source folders too. The standard library, org.jetbrains.kotlin:kotlin-stdlib, is an ordinary dependency. Workspace modules inherit it from the root.

Type: a string, or an object. Workspace modules inherit it from the root.

- `version` (a string): The Kotlin version, exact, such as 2.2.20.
- `plugins` (a list of strings): Compiler plugins: group:artifact (the Kotlin version) or group:artifact:version, or the short name of one of Kotlin's own (assignment, sam-with-receiver, allopen, noarg, serialization, lombok, power-assert, parcelize, atomicfu, js-plain-objects, compose). Their options go in args as -P plugin:<id>:<option>=<value>.
- `args` (a list of strings): Extra arguments for kotlinc, e.g. -Xjsr305=strict; -J entries go to the JVM running it, e.g. -J-Xmx2g.

### `exclude`

Packages left out of everything the runtime and dev dependencies bring in, like an exclusion on each of them (Gradle's configurations.all { exclude(...) }), e.g. for what the platform provides: group:artifact, group:* or group. Test dependencies are not affected. A workspace module adds its own to the root's.

Type: a list of strings. Workspace modules inherit it from the root.

### `filter`

Resources (globs relative to the resource folder, e.g. "*.properties") copied with each ${name} replaced. Values, later ones winning: the module's NAME and VERSION; fields of the workspace root's project.jsonc, then the module's (nested ones dotted, e.g. ${extensions.publish.repo}); *.properties next to the root's project.jsonc, then next to the module's; then the module's define values.

Type: a list of strings.

### `relocate`

Packages moved to new names in the flat bundle (like Shadow's relocation): package to new package, e.g. {"org.yaml.snakeyaml": "com.example.shaded.snakeyaml"}. Classes, descriptors, string constants naming classes, and service files are rewritten.

Type: an object.

### `layout`

Where sources live. jpm: src/, src/assets/, test/, test/assets/. maven (or gradle): src/main/java, src/main/resources, src/test/java, src/test/resources.

Type: one of `"jpm"`, `"maven"`, `"gradle"`. Workspace modules inherit it from the root.

### `package`

What `jpm build` produces: a jar, or a bundle with the runtime dependencies (a runnable jar-in-jar with internal modules isolated when there is a main, one flat jar when there is none, as plugins need).

Type: one of `"jar"`, `"bundle"`.

### `extensions`

The extensions this project uses and their settings. A dependency key (with a colon) declares an extension with its version, in the workspace root only; jpm pins it in project.lock and runs it as `jpm <name>`, where <name> is its artifact without the jpm- prefix. A plain name holds that extension's settings, which jpm passes through untouched.

Type: an object.

- `publish` (an object): Settings for `jpm publish` (the jpm-publish extension). A module's settings override the workspace root's key by key.
  - `repositories` (an object): Repositories by name; `jpm publish <name>` publishes to one. Credentials come from JPM_PUBLISH_<NAME>_USERNAME and _PASSWORD.
  - `developers` (a list of objects): Required by Maven Central.
  - `scm` (a string): The source repository's URL. Defaults to "url" on GitHub, GitLab and Codeberg.
  - `javadoc` (`true` or `false`): Publish a javadoc jar to repositories other than Maven Central too.
  - `sign` (`true` or `false`): Sign with gpg. Defaults to true for Maven Central only.
  - `skip` (`true` or `false`): Do not publish this module.

### `extension`

Marks this project as a jpm extension, so `jpm add` declares it in "extensions" instead of adding it as a dependency, and describes what it adds; jpm serves the description to IDEs (extensions/list) without running the extension.

Type: an object.

- `description` (a string): What the extension does.
- `commands` (an object): Its commands, by what follows `jpm` ("publish", or "format check" for a subcommand); each starts with the extension's name without the jpm- prefix.
  - each entry is an object; an object has:
    - `description` (a string): What the command does, shown in IDEs and in `jpm help`.
    - `options` (an object): Options by name ("--dry-run").
- `checks` (an object): Checks it adds to `jpm check`, by id.
  - each entry is an object; an object has:
    - `description` (a string): What the check checks, shown in `jpm check`'s output.
    - `run` (a list of strings): The arguments `jpm check` runs the extension with (default: check <id>). A non-zero exit fails the check; problems the extension writes to the file in JPM_CHECK_PROBLEMS (a JSON array of {severity, message, file?, line?, hint?}) are reported with their locations.

<!-- /fields -->
