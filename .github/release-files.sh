#!/bin/sh
# Collects a release's files into release/: the binaries from the build job's artifacts, the install
# scripts, the manifest schema, VERSION (what `jpm upgrade` reads) and SHA256SUMS.
#
#   .github/release-files.sh <version>
set -eux
version="$1"
rm -rf release && mkdir release
for d in artifacts/jpm-*; do
  platform="${d#artifacts/jpm-}"
  for f in "$d"/*; do
    case "$f" in *.exe) cp "$f" "release/jpm-$platform.exe" ;; *) cp "$f" "release/jpm-$platform" ;; esac
  done
done
cp install.sh install.ps1 cli/src/assets/dev/jpm/project.schema.json release/
printf '%s\n' "$version" > release/VERSION
cd release && sha256sum * > SHA256SUMS && cat SHA256SUMS
