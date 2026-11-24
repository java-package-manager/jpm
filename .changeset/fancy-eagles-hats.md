---
"jpm": minor
---

`project.lock` records the repository each Maven package came from, and jpm looks there first, then on the same host, then in the other repositories, so it asks fewer repositories for every file; `jpm check` warns about a listed repository no locked package comes from
