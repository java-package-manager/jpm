---
"jpm": minor
---

Code generators: a script with `"generates": "sources"` or `"resources"` runs before compiling into its own folder (`JPM_GENERATED`), again only when its `"inputs"` change, and that folder joins the module's sources or resources; scripts that are not .java files get their dependencies in `JPM_SCRIPT_CLASSPATH`.
