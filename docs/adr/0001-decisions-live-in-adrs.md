# 0001. Decisions live in ADRs, not in code comments

Status: accepted

## Context

Comments drift from the code they describe. Many repeat what the code says, and some excuse a hacky
implementation, which then gets copied. The few that matter record a decision that is not obvious from
the code, and those are hard to find when they are spread over a hundred files.

## Decision

- jpm's Java sources have no `//` or `/* */` comments. `checks/Ratchets.java` counts them, and the count
  may only go down (`checks/ratchets.properties`).
- A non-obvious decision goes into an ADR in `docs/adr/`, which names the code it is about.
- Javadoc (`/** */`) is not a comment. It is required on the public API (`dev.jpm.api`) and on internal
  API (the types areas use to talk to each other), and unwanted on implementations.
- A comment that excuses a hack becomes an issue to fix the hack.
- `project.jsonc` comments are documentation of the manifest, not code, and stay.
- jpm reads no Java or Kotlin sources itself (roadmap Phase 3): what it needs comes from javac, compiled
  classes or the manifest. `Fs.read` refuses `.java` and `.kt` files, and the `text-reads-outside-fs`
  ratchet counts text reads that bypass it.
- The `/delete-comment` skill (`.claude/skills/delete-comment`) repeats the process.
