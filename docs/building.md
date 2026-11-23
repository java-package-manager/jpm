# Building

`jpm build` calls the selected JDK's `javac`, with `--release` only when it is newer than the module's
target (so an API added later is a compile error, not a runtime crash). On a JDK of the target version
`--release` changes nothing, and leaving it out lets `javacArgs` open the JDK's own modules, e.g.
`["--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED"]`, which javac refuses together
with `--release`. Per module, a hash of the classpath, define values, JDK and
flags decides whether to reuse output. Per file, changed sources are recompiled; when a class's public
signature changes, every source that refers to it is recompiled too, and a change to a compile-time
constant rebuilds the module (javac inlines constants without leaving a reference). Dev dependencies are
the annotation processor path. Jars are reproducible.

**Defines** generate `BuildConfig` (in the main class's package, or `defineClass`) with `NAME`,
`VERSION` and one typed constant per define. Values come from the consumer (for libraries), the env
variable, the literal value, then the default. Libraries may declare defines without a value; a missing
or mistyped value fails with the library, variable, type and the exact snippet to add.

**Env files** load like Bun: `.env`, then `.env.development` (`dev`, `test`, `run`) or
`.env.production` (`build`, `start`), then `.env.local`, which overrides the other files. Variables
already set in the process environment win over all files, so CI secrets are never shadowed.

**Patches.** `jpm patch <dep>` unpacks the Git checkout or the Maven sources jar into
`.cache/patch/<name>` as a Git repo; `jpm patch --commit <dep>` writes the diff to `patches/` and
records it in the manifest. Git dependencies are patched before they are built. For Maven jars only the
changed classes are recompiled and swapped in. If a patch no longer applies, the build fails. A patch
can also be an object, `{ "file": "patches/x.patch", "dependencies": { "net.kyori:adventure-api": "5.2.0" },
"release": 25 }`: `dependencies` are compile-only for the patched classes (resolved and locked with the
project, never on anyone's classpath), and `release` compiles them for a newer Java than the library's
own bytecode level. Modular jars are patched as their module (`--patch-module`), so a patch can change
`module-info.java` and use package-private members.

## Java modules

A module with `src/module-info.java` (or `src/main/java/module-info.java` in the Maven layout) builds
and runs as a named module, with nothing to configure:

- **Compiling:** dependencies that are modules (they have a `module-info.class` or an
  `Automatic-Module-Name`), and any jar whose automatic module name your `module-info.java`
  `requires`, go on the module path. Everything else stays on the class path, which the module can
  read. `requires static` fits `dev` dependencies such as annotations. A named module is always compiled
  as a whole; the per-module cache still skips it when nothing changed.
- **Running:** `jpm start` and `jpm dev` start it with `-m <module>/<main>`, so `provides`, `uses`,
  exports and encapsulation behave as declared; resources in `src/assets/` are part of the module.
- Tests compile and run on the class path, so they can reach internal packages. `java -jar` and
  bundles also use the class path, where the JDK ignores `module-info`.

## Kotlin

A module with Kotlin sources names the Kotlin version, and depends on the standard library like on any library:

```jsonc
{
  "name": "my-app",
  "kotlin": "2.2.20",
  "runtime": { "org.jetbrains.kotlin:kotlin-stdlib": "2.2.20" },
  "test": { "org.jetbrains.kotlin:kotlin-test-junit5": "2.2.20" }
}
```

`.kt` files go beside the `.java` files in `src/` (and `test/`); in the Maven layout, `src/main/kotlin` and
`src/test/kotlin` are source folders too. kotlinc (`org.jetbrains.kotlin:kotlin-compiler-embeddable` of that
version, resolved on its own like any tool) compiles first, seeing the Java sources and the defines class, so
the two languages can use each other; javac then compiles the Java against its output. Tests see the module's
`internal` declarations. A module's Kotlin is compiled as a whole when a source, the classpath or a setting
changes. A module with Kotlin sources only gets its defines class as Kotlin (an `object` with `const val`s,
which Java code reads as static final fields), so it needs no Java at all.

Compiler plugins and flags:

```jsonc
"kotlin": {
  "version": "2.2.20",
  "plugins": ["allopen", "serialization", "com.example:my-plugin:1.0"],   // Kotlin's own by name
  "args": ["-Xjsr305=strict", "-P", "plugin:org.jetbrains.kotlin.allopen:annotation=my.Open", "-J-Xmx2g"]
}
```

`plugins` are Maven coordinates (`group:artifact`, with the Kotlin version, or `group:artifact:version`) or the
names of Kotlin's own: `assignment`, `sam-with-receiver`, `allopen`, `noarg`, `serialization`, `lombok`,
`power-assert`, `parcelize`, `atomicfu`, `js-plain-objects`, `compose`. `-J` entries in `args` go to the JVM
running kotlinc. Workspace modules inherit `kotlin` from the root.

## Groovy and Scala

`"groovy": "4.0.24"` or `"scala": "3.5.2"` (Scala 2.13 works too) compiles the module's `.groovy` or `.scala`
sources, in its source folders (and `src/main/groovy` or `src/main/scala`, with the test folders to match, in
the `maven` layout), before javac. groovyc compiles jointly with the Java sources, and the Scala compiler
reads them, so Java and the other language can use each other's classes. The compiler is downloaded from
Maven Central like any tool; the runtime library is an ordinary dependency, and jpm says which to add when
it is missing (`org.apache.groovy:groovy`, before Groovy 4 `org.codehaus.groovy:groovy`;
`org.scala-lang:scala3-library_3`, for 2.13 `org.scala-lang:scala-library`). Workspace modules inherit both
fields from the root.

```jsonc
{ "name": "app", "version": "1.0.0", "scala": "3.5.2", "runtime": { "org.scala-lang:scala3-library_3": "3.5.2" } }
```

## Gradle plugins

`gradle:api` is the Gradle API, as Gradle's `gradleApi()` gives it to plugins. Gradle publishes no API jar
for current versions, so jpm downloads the distribution of that Gradle version (`gradle-9.8.0-bin.zip` from
services.gradle.org, or `JPM_GRADLE_DISTRIBUTIONS`) once into `~/.jpm/gradle/9.8.0` and puts its jars on the
classpath: the API, the Kotlin DSL extensions, TestKit, `ProjectBuilder`, and what Gradle provides to plugins
(Groovy, the Kotlin standard library, SLF4J). The archive's SHA-256 is locked in `project.lock`.

```jsonc
{
  "name": "my-gradle-plugin",
  "dev":  { "gradle:api": "9.8.0" },   // compiled against, provided by Gradle at run time
  "test": { "gradle:api": "9.8.0" }    // ProjectBuilder and TestKit tests
}
```

For a module with `gradle:api`, jpm also does what Gradle's `java-gradle-plugin` does for tests: the test
classpath has `plugin-under-test-metadata.properties`, so TestKit's `GradleRunner.withPluginClasspath()` runs
the plugin under test, and the test JVM gets the `--add-opens` options Gradle gives its own test workers. TestKit
runs the builds on the downloaded distribution. Plugin descriptors (`META-INF/gradle-plugins/<id>.properties`)
are ordinary resources. Plugins written in Kotlin with the `kotlin-dsl` conventions use the `assignment` and
`sam-with-receiver` compiler plugins with Gradle's annotations:

```jsonc
"kotlin": {
  "version": "2.4.10",   // the Kotlin that Gradle 9.8 embeds
  "plugins": ["sam-with-receiver", "assignment"],
  "args": [
    "-jvm-default=no-compatibility", "-Xjsr305=strict", "-Xsam-conversions=class",
    "-P", "plugin:org.jetbrains.kotlin.samWithReceiver:annotation=org.gradle.api.HasImplicitReceiver",
    "-P", "plugin:org.jetbrains.kotlin.assignment:annotation=org.gradle.api.SupportsKotlinAssignmentOverloading"
  ]
},
"exclude": ["org.jetbrains.kotlin", "org.slf4j"]   // Gradle provides them: keep them out of the plugin's bundle
```

A shaded plugin jar is a flat bundle with `"relocate"` (see below). jpm-publish does not write Gradle plugin
marker artifacts or publish to the Gradle Plugin Portal.

## Bundles (jar-in-jar isolation)

For a module without a `main`, such as a plugin that a server loads, or with `jpm build --flat`, the bundle
is one flat jar instead: the module's classes and resources with those of every runtime dependency merged
in, the first copy of a file winning except service files (`META-INF/services/*`), which are joined. `dev`
dependencies stay out, as the platform provides them. `"relocate": { "org.yaml.snakeyaml": "com.example.shaded.snakeyaml" }`
moves packages to new names in it, as Shadow does: class references, descriptors, string constants naming
classes or resources, multi-release entries and service files are rewritten (names computed at run time are
not).

For a module with a `main`, `jpm build --bundle` (or `"package": "bundle"`) writes `dist/<name>-<version>-bundle.jar`, which runs
with `java -jar`. Nothing is shaded: the app and every dependency stay untouched jars inside it.

- The app and its dependencies load in a **shared** class loader whose parent is the JDK.
- Each **internal** implementation module (an `internal` workspace module pulled in by a `public` one,
  locally or from Git) gets its own **isolated** loader. It checks its own jars first, holding the
  module and the dependencies only it uses. The API module's packages and the JDK always come from the
  shared loader, so types match on both sides.
- The context class loader sees every isolated module, so registries that look implementations up by
  name (`ServiceLoader`, `Class.forName(name, true, contextLoader)`) find them, while the registry
  classes themselves stay in the shared loader.
- jpm generates the launcher and class loaders, compiled for the module's target (Java 8 or newer).
  Nested jars are unpacked once into a cache directory named after the bundle's contents
  (`$JPM_BUNDLE_CACHE`, or the temp directory).

Isolation controls what each part of the program can see: the app cannot reach an implementation's
private dependencies, and an implementation's dependencies never leak onto the app's classpath. Versions
are still resolved once for the whole graph, so an isolated module cannot use a different version of a
package that the app also uses.

## Native builds

`jpm build --native` builds the jar, then runs native-image from a GraalVM of the
project's Java version (any installed GraalVM-based JDK, else GraalVM Community, installed) and writes
`dist/<name>`. Extra flags go in `"nativeArgs"`. `jpm start --trace` runs the app under the tracing
agent and merges what it records into `src/assets/META-INF/native-image/<group>/<name>/`, which ships in the
jar and is picked up by native-image automatically. `jpm test --trace` does the same for a test run, so the
tests' coverage of reflection, resources and proxies goes into the configuration too; what JUnit itself does
and what is done to the test classes is filtered out, since the configuration is for the application.
