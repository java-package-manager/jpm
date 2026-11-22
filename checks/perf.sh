#!/bin/sh
# Times no-op commands of the jpm binary on fixtures/bundle-demo and fails when one is over its budget in
# checks/perf-budget.properties. Usage: checks/perf.sh <jpm binary>
set -eu
cd "$(dirname "$0")/.."
JPM="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp -r fixtures/bundle-demo "$work/"
cd "$work/bundle-demo"
export JPM_NO_UPDATE_CHECK=1
"$JPM" build >/dev/null
now() { perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'; }
failed=0
for cmd in install build info; do
  times=""
  for i in 1 2 3 4 5; do
    t0=$(now); "$JPM" "$cmd" >/dev/null 2>&1; t1=$(now)
    times="$times $((t1 - t0))"
  done
  median=$(printf '%s\n' $times | sort -n | sed -n 3p)
  budget=$(sed -n "s/^$cmd=//p" "$OLDPWD/checks/perf-budget.properties")
  if [ "$median" -gt "$budget" ]; then status="OVER BUDGET"; failed=1; else status="ok"; fi
  printf 'jpm %-8s %5d ms  (budget %d ms, runs:%s)  %s\n' "$cmd" "$median" "$budget" "$times" "$status"
done
exit $failed
