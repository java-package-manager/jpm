#!/usr/bin/env python3
"""Updates jpm's copy of the SPDX license list from https://spdx.org/licenses/licenses.json.

Writes cli/src/assets/dev/jpm/spdx-licenses.json (what jpm reads: each license's id, name, whether it is
deprecated, and its reference URLs) and the list of ids in the schema's "license" field. Run it when SPDX
publishes a new list; the build itself never goes to the network.

Usage: python3 schema/spdx.py [path/to/licenses.json]   (downloads the list when no file is given)
"""
import json
import pathlib
import re
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSET = ROOT / "cli/src/assets/dev/jpm/spdx-licenses.json"
SCHEMA = ROOT / "cli/src/assets/dev/jpm/project.schema.json"
URL = "https://spdx.org/licenses/licenses.json"
EXPRESSION = r"^\(?[A-Za-z0-9.+-]+( (AND|OR|WITH) \(?[A-Za-z0-9.+-]+\)?)+$"


def main() -> None:
    if len(sys.argv) > 1:
        data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
    else:
        with urllib.request.urlopen(URL, timeout=60) as r:
            data = json.loads(r.read().decode("utf-8"))
    licenses = sorted(data["licenses"], key=lambda x: x["licenseId"].lower())
    asset = {
        "version": data["licenseListVersion"],
        "source": URL,
        "licenses": [
            {"id": x["licenseId"], "name": x["name"], "deprecated": x.get("isDeprecatedLicenseId", False),
             "urls": x.get("seeAlso", [])}
            for x in licenses
        ],
    }
    ASSET.write_text(json.dumps(asset, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    ids = [x["licenseId"] for x in licenses if not x.get("isDeprecatedLicenseId", False)]
    entry = {
        "description": f"The license: an SPDX license id (SPDX license list {data['licenseListVersion']},"
                       " https://spdx.org/licenses/), or an SPDX expression such as \"MIT OR Apache-2.0\". Published"
                       " POMs name it, and `jpm license <id>` writes the license file.",
        "anyOf": [
            {"enum": ids},
            {"type": "string", "pattern": EXPRESSION, "description": "An SPDX license expression."},
        ],
        "examples": ["MIT", "Apache-2.0", "GPL-3.0-or-later", "MIT OR Apache-2.0"],
    }
    text = SCHEMA.read_text(encoding="utf-8")
    m = re.search(r'\n    "license": \{\n.*?\n    \}', text, re.S)
    if not m:
        sys.exit("no \"license\" property in " + str(SCHEMA))
    body = json.dumps(entry, indent=2, ensure_ascii=False).replace("\n", "\n    ")
    text = text[:m.start()] + '\n    "license": ' + body + text[m.end():]
    SCHEMA.write_text(text, encoding="utf-8")
    print(f"SPDX license list {data['licenseListVersion']}: {len(licenses)} licenses ({len(ids)} not deprecated)")


if __name__ == "__main__":
    main()
