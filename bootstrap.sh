#!/bin/sh
# Builds jpm with plain javac (both modules, on the class path), then lets jpm build itself into cli/dist/.
set -eu
cd "$(dirname "$0")"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR_OUT=.cache/bootstrap/jpm.jar
rm -rf .cache/bootstrap
mkdir -p .cache/bootstrap/classes .cache/bootstrap/gen/dev/jpm

# jpm generates dev.jpm.BuildConfig from project.jsonc; the bootstrap writes it by hand.
VERSION=$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' project.jsonc | head -n 1)
printf 'package dev.jpm;\npublic final class BuildConfig {\n    private BuildConfig() {}\n    public static final String NAME = "jpm";\n    public static final String VERSION = "%s";\n}\n' "$VERSION" \
  > .cache/bootstrap/gen/dev/jpm/BuildConfig.java

find api/src cli/src .cache/bootstrap/gen -name '*.java' ! -name module-info.java > .cache/bootstrap/sources.txt
"$JAVAC" --release 21 -encoding UTF-8 -d .cache/bootstrap/classes @.cache/bootstrap/sources.txt
printf 'Main-Class: dev.jpm.cli.Main\n' > .cache/bootstrap/manifest.txt
"${JAVA_HOME:+$JAVA_HOME/bin/}jar" --create --file "$JAR_OUT" --manifest .cache/bootstrap/manifest.txt -C .cache/bootstrap/classes .
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$JAR_OUT" build
echo "Done. Put $(pwd)/bin on your PATH to use jpm."
