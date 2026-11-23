---
"jpm": minor
---

`jpm install` resolves merge conflicts in project.lock: it reads both sides, starts from the higher version of each package and resolves again; `--frozen` fails on a lockfile with conflict markers.
