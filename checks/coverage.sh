#!/bin/sh
# Runs the unit and end-to-end tests with JaCoCo and writes a report to .cache/coverage/
# (index.html, coverage.xml, and summary.md with the coverage of each package).
# Usage: checks/coverage.sh  (JPM=<jpm executable> runs the tests with that jpm instead of bin/jpm)
set -eu
cd "$(dirname "$0")/.."

JACOCO=0.8.13
out=.cache/coverage
tools="$out/tools"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p "$tools"
for jar in org.jacoco.agent-$JACOCO-runtime.jar org.jacoco.cli-$JACOCO-nodeps.jar; do
  [ -f "$tools/$jar" ] && continue
  artifact=${jar%%-$JACOCO*}
  curl -sSfL -o "$tools/$jar" "https://repo.maven.apache.org/maven2/org/jacoco/$artifact/$JACOCO/$jar"
done

rm -f "$out/jacoco.exec"
exec_file="$(pwd)/$out/jacoco.exec"
agent="$(pwd)/$tools/org.jacoco.agent-$JACOCO-runtime.jar"
status=0
JPM_TEST_JVM_ARGS="-javaagent:$agent=destfile=$exec_file,append=true,includes=dev.jpm.*,excludes=dev.jpm.e2e.*:dev.jpm.testing.*" \
  "${JPM:-bin/jpm}" test --frozen --exclude-tag live || status=$?

"$JAVA" -jar "$tools/org.jacoco.cli-$JACOCO-nodeps.jar" report "$out/jacoco.exec" \
  --classfiles .cache/build/jpm/classes --classfiles .cache/build/jpm-api/classes \
  --sourcefiles cli/src --sourcefiles api/src --sourcefiles .cache/build/jpm/generated \
  --html "$out/html" --xml "$out/coverage.xml" --name "jpm" >/dev/null

"$JAVA" checks/CoverageSummary.java "$out/coverage.xml" > "$out/summary.md"
cat "$out/summary.md"
exit $status
