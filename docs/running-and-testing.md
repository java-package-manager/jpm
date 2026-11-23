# Running and testing

`jpm start` builds and runs `main` with the production env; `jpm dev` runs it with the development env and
reloads code as you change it; `jpm test` runs the tests.

## Tests

Tests run on the JUnit Platform console launcher matching the resolved platform version; any
platform engine works. `jpm test com.example.FooTest`, `jpm test 'com.example.*'` or
`jpm test 'com.example.FooTest#bar'` select tests; `--tag <expr>` and `--exclude-tag <expr>` select
by JUnit tag (`jpm test --exclude-tag live`). In a workspace, a class runs in the modules whose tests have
it; naming a class no module has is an error. `JPM_TEST_JVM_ARGS` adds options to the JVM running the
tests, for example a coverage agent (`-javaagent:jacocoagent.jar=destfile=jacoco.exec`).
`jpm test --reports <dir>` writes JUnit XML reports (`TEST-*.xml`) for CI to show, in `<dir>/<module>` in a
workspace. `jpm test --watch` runs the tests again whenever a source, test, resource or manifest
changes, until Ctrl+C.

## Hot reload

`jpm dev` runs the app with a small agent of jpm's. When only the module's code changes, jpm
recompiles it and redefines the classes that changed in the running JVM, so the app keeps its state; a
compile error leaves it running the code it had. The JVM can only replace method bodies, so a change that adds
or removes members, or a change to anything else (resources, the manifest, env files, other modules),
restarts the app. On JetBrains Runtime (`"java": "jetbrains-21"`), jpm adds
`-XX:+AllowEnhancedClassRedefinition`, which takes structural changes too. `jpm dev --restart` always
restarts.

## Scripts and hooks

Scripts run with `jpm run <name>`. `.java` scripts run on the source launcher with the runtime
classpath. A script that needs libraries of its own lists them with it:
`"generate": { "run": "scripts/Generate.java", "dependencies": { "com.google.code.gson:gson": "2.11.0" } }`;
they are resolved on their own, come first on the script's classpath and never move the project's versions
(a script that is not a `.java` file gets them in `JPM_SCRIPT_CLASSPATH`). `.sh` scripts with `sh`; anything else as a shell command. Scripts and extensions get
`JPM_PROJECT_DIR`, `JPM_MODULE_DIR`, `JPM_NAME`, `JPM_VERSION`, `JPM_MANIFEST`, `JPM_CLASSES_DIR`,
`JPM_CLASSPATH`, `JPM_JAR` (after a build), `JPM_JAVA_HOME`, and `JPM_EXE` and `JPM_VERSION_OF_JPM` to
call back into the jpm running them; extensions also get `JPM_CONFIG`.
Hooks are `pre`/`post` + `build`/`test`/`start`, optionally with `:<profile>` (`build --release` is
profile `release`).

## Code generators

Code generators are scripts with `"generates": "sources"` (or `"resources"`), what Maven and Gradle do with
code-generating plugins. Before compiling, jpm runs each one into a folder of its own, `JPM_GENERATED`, and
adds that folder to the module's sources (or resources); it runs again only when a file under its `"inputs"`
or its own definition changed. For example ANTLR:

```jsonc
"scripts": {
  "parser": {
    "run": "java -cp $JPM_SCRIPT_CLASSPATH org.antlr.v4.Tool -o $JPM_GENERATED/app -package app src/grammar/Calc.g4",
    "dependencies": { "org.antlr:antlr4": "4.13.2" },
    "generates": "sources",
    "inputs": ["src/grammar"]
  }
}
```
