---
"jpm": minor
---

`jpm test --trace` runs the tests under the native-image tracing agent and merges what they use into the configuration `jpm build --native` reads, leaving out what JUnit and the test classes do.
