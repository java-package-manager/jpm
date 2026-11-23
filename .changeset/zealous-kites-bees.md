---
"jpm": patch
---

`jpm audit` sends each package to OSV with its fields in a fixed order, so the same lock always gives the same request.
