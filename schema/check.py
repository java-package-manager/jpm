#!/usr/bin/env python3
"""Validates project.jsonc files against jpm's schema, cli/src/assets/dev/jpm/project.schema.json.

Usage: python3 schema/check.py [--expect-invalid] FILE...   (needs `pip install jsonschema`)
"""
import json
import pathlib
import sys

import jsonschema


def strip_jsonc(text: str) -> str:
    """Removes // and /* */ comments and trailing commas, leaving strings alone."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
        else:
            out.append(c)
            i += 1
    s = "".join(out)
    # trailing commas before } or ]
    res, k = [], 0
    for idx, ch in enumerate(s):
        if ch in "}]":
            back = len(res) - 1
            while back >= 0 and res[back].isspace():
                back -= 1
            if back >= 0 and res[back] == ",":
                del res[back]
        res.append(ch)
    return "".join(res)


def main(args):
    expect_invalid = "--expect-invalid" in args
    files = [a for a in args if a != "--expect-invalid"]
    schema = json.loads((pathlib.Path(__file__).parent.parent / "cli/src/assets/dev/jpm/project.schema.json").read_text())
    jsonschema.Draft7Validator.check_schema(schema)
    validator = jsonschema.Draft7Validator(schema, format_checker=jsonschema.FormatChecker())
    failed = False
    for f in files:
        data = json.loads(strip_jsonc(pathlib.Path(f).read_text()))
        errors = sorted(validator.iter_errors(data), key=lambda e: list(e.path))
        if expect_invalid:
            if not errors:
                print(f"{f}: expected schema errors, found none")
                failed = True
            else:
                print(f"{f}: rejected as expected ({errors[0].message[:80]})")
        elif errors:
            failed = True
            for e in errors:
                print(f"{f}: {'/'.join(map(str, e.path)) or '(root)'}: {e.message}")
        else:
            print(f"{f}: ok")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
