---
"jpm": minor
---

Hot reload: `jpm dev` redefines the classes a code change touches in the running JVM instead of restarting it (method bodies on any JDK, structural changes too on JetBrains Runtime), and restarts when that is not possible; `jpm dev --restart` always restarts.
