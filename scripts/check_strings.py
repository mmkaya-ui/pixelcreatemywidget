#!/usr/bin/env python3
"""Checks that every language has every string, with the same placeholders, and that nothing in the
app code points at a string that does not exist. Run by scripts/check.sh."""
import re, sys, glob, os, xml.etree.ElementTree as ET

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
res = os.path.join(root, "app/src/main/res")
LANGS = ["values", "values-de", "values-tr"]
PLACEHOLDER = re.compile(r"%(\d+)\$[sd]")

def load(d):
    out = {}
    for s in ET.parse(os.path.join(res, d, "strings.xml")).getroot().iter("string"):
        out[s.attrib["name"]] = "".join(s.itertext())
    return out

tables = {d: load(d) for d in LANGS}
base = tables["values"]
problems = []
for d in LANGS[1:]:
    t = tables[d]
    for k in base:
        if k not in t: problems.append(f"{d}: missing {k}")
        elif sorted(PLACEHOLDER.findall(t[k])) != sorted(PLACEHOLDER.findall(base[k])):
            problems.append(f"{d}: {k} has different placeholders than English")
        elif not t[k].strip(): problems.append(f"{d}: {k} is empty")
    for k in t:
        if k not in base: problems.append(f"{d}: {k} is not in English")
for k, v in base.items():
    if re.search(r"%(?![0-9]+\$[sd]|%)", v): problems.append(f"values: {k} has a bare % (write %% in a formatted string, and give it arguments)")

# Every R.string.x used in code exists.
used = set()
for f in glob.glob(os.path.join(root, "app/src/**/*.kt"), recursive=True):
    used |= set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", open(f, encoding="utf-8").read()))
for f in glob.glob(os.path.join(root, "app/src/main/**/*.xml"), recursive=True):
    used |= set(re.findall(r"@string/([A-Za-z0-9_]+)", open(f, encoding="utf-8").read()))
for k in sorted(used):
    if k not in base: problems.append(f"code uses R.string.{k}, which does not exist")
# Dynamic lookups ("new_" + name, ...) are checked by prefix in the code; unused strings are only reported.
unused = [k for k in base if k not in used and not re.match(r"(new_|type_|opt_|size_|align_|from_|width_|ink_|fill_|palette_|kind_|privacy_)", k)]
for k in unused: print("note: string not used anywhere:", k)

if problems:
    print("\n".join(problems)); sys.exit(1)
print(f"strings ok: {len(base)} in {len(LANGS)} languages")
