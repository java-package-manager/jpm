---
name: delete-comment
description: Find the // and /* */ comments in this repository (or a part of it), categorize each one, and act on it - delete tautologies and stale comments, turn excuses into issues, and move decision records into docs/adr. Use when comments have crept back in (the comments ratchet fails), or when asked to clean up comments.
---

# Delete comments

jpm's Java sources have no `//` or `/* */` comments ([ADR 0001](../../../docs/adr/0001-decisions-live-in-adrs.md)).
Javadoc (`/** */`) is not a comment and is out of scope. `project.jsonc` comments are documentation and stay.

## 1. List them

```sh
java checks/Comments.java list src test        # or a narrower path
```

Each comment is printed with the code it belongs to (`on:` for a trailing comment, `next:` for the line below).

## 2. Categorize every comment

| Category | What it is | Action |
|---|---|---|
| Tautology | Repeats what the code says (`// minor`, section dividers, `// trailing comma`) | Delete |
| Stale | Refers to code or behavior that no longer exists | Delete |
| Excuse | Justifies a hacky implementation ("guess", "for now", heuristics, matching error text) | Delete, and write an issue to fix the hack |
| Decision record | A non-obvious decision: why the code does this and not the obvious thing | Move into an ADR, then delete |

A comment that documents a member of an API type (a record component, a parameter) becomes Javadoc
instead, for example `@param`.

## 3. Act

- **Decision records:** add each to the ADR in `docs/adr/` that covers its area (the table in
  `docs/adr/README.md`), naming the class it is about. If no ADR fits, write the next number, in the same
  format (Status, Context when needed, Decision, Known problem), and add it to the table. Check what you
  write against the code: an ADR states facts, not what the comment hoped.
- **Excuses:** draft one issue per hack: title, where it is, what is wrong, what a fix looks like. Check
  `ROADMAP.md` first: if the roadmap covers it already, reference that instead. Ask the user before filing
  issues on GitHub.
- **Then delete them all:** `java checks/Comments.java strip <paths>`. It removes a comment on a line of
  its own with its line, and a trailing one with the space before it; strings, text blocks and Javadoc are
  left alone. Review `git diff`: the only added lines should be lines that lost a trailing comment.

## 4. Check and ratchet

```sh
bin/check                              # build, tests, schema, ratchets
java checks/Ratchets.java --update     # lower the comments baselines to the new counts
```

Not every comment has to go in one change: the baselines in `checks/ratchets.properties` only go down,
so a partial clean-up is fine as long as nothing new comes in.

## 5. Report

Say how many comments were in each category, which ADRs changed, which issues you drafted, and how many
lines the change removes (`git diff --stat`).
