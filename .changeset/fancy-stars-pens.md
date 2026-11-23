---
"jpm": minor
---

`jpm sbom` writes a CycloneDX 1.6 software bill of materials (JSON) for the locked dependencies: package URLs, SHA-256 hashes from project.lock, licenses from POMs, whether each package ships at run time, and the dependency graph; the same lock gives the same file. `-o <file>` writes it to a file.
