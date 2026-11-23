---
"jpm": minor
---

Licenses: `jpm license <SPDX id>` sets "license" and writes LICENSE from SPDX's text (`jpm init --license` too); the schema lists every SPDX id; `jpm import` takes the license from the POM or the license file; `jpm check` reports ids that are not on the SPDX list and a "license" that does not match the license file.
