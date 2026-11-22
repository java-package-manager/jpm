# 0014. Editing `project.jsonc` in place

Status: accepted

## Decision

jpm edits manifests as text, so comments and formatting survive (`JsoncEdit`):

- A new nested value is built as a fresh multi-line value and inserted after the line that holds the last
  member, keeping that line's trailing comment.
- Removing a member removes from the start of its line (when only whitespace precedes the key) to after
  its comma, with a trailing line comment and the newline. For the last member, the comma of the previous
  member goes instead.
- When resolution fails after `jpm add`, the manifests are written back exactly as they were
  (`Commands`). Repositories are always read from the root manifest (the workspace root in a workspace).

`.env` files take multi-line quoted values (`Env`).
