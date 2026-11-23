---
"jpm": minor
---

A minimum release age: `"minimumReleaseAge"` in the workspace root makes jpm pick only versions at least that old, and `jpm check` fails on a younger locked version unless `"releaseAgeExceptions"` names it.
