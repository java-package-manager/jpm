---
"jpm": patch
---

On Windows, jpm deletes read-only files when it cleans up a folder, such as the Git objects of a `jpm patch` checkout, so `jpm patch --commit` no longer fails there.
