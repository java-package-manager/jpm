---
"jpm": minor
---

Signatures: with `"trustedKeys"` (key fingerprints and the packages each may sign) in the workspace root, `jpm install` checks the OpenPGP signature published next to every jar: a signed jar must be signed by a trusted key and match its signature
