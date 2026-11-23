# 0016. Licenses

Status: accepted

## Decision

- jpm carries a copy of the SPDX license list (`cli/src/assets/dev/jpm/spdx-licenses.json`: each license's id,
  name, whether it is deprecated, and its reference URLs), and the schema's `"license"` lists the same ids.
  `schema/spdx.py` updates both from https://spdx.org/licenses/licenses.json; builds and tests never go to
  the network for it. A test keeps the two in step, and a weekly workflow (`.github/workflows/spdx.yml`)
  keeps them current: when SPDX publishes a new list it opens a pull request with it and a patch changeset. The schema also accepts SPDX expressions
  (`MIT OR Apache-2.0`), and `LicenseRef-` ids are not checked.
- Ids match ignoring case, as SPDX says; a misspelled id gets the closest current id as a suggestion (edit
  distance with swapped letters as one edit, or a missing `-only`/`-or-later`).
- A license file's text is recognised by phrases its license always has, for the common licenses only (MIT,
  Apache-2.0, the BSDs, the GPL family, MPL-2.0, EPL, ISC, 0BSD, Unlicense, CC0-1.0, BSL-1.0). The GPL texts
  do not say "only" or "or later", so either id matches them, and `-or-later` is the one suggested.
- A POM's `<licenses>` become an `OR` expression of the ids their names or URLs are on the list with; a
  license the list does not have is left out with an import warning.
- `jpm license <id>` and `jpm init --license` download the license text from SPDX when they write the file
  (`JPM_SPDX_BASE` replaces https://spdx.org/licenses, which the tests use), rather than carrying every text:
  the full texts are megabytes, and writing a license file is rare. The year and the holder (`--author`, else
  git's `user.name`) replace SPDX's placeholders. An existing license file is kept unless `--force`.
