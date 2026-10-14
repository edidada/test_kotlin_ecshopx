#!/usr/bin/env python3
"""Extract all Dingo/Lumen routes from ECShopX route files into JSON."""
import json, os, re
from collections import Counter

ROOT = r"D:\develops\git\github\java\test_kotlin_ecshopx\thirdparty\ECShopX\routes"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "routes_raw.json")

VERB = re.compile(r"->(get|post|put|patch|delete|any|resource)\(\s*['\"]([^'\"]+)['\"]")
GROUP = re.compile(r"->group\(")
VERSION = re.compile(r"->version\(")
KEY = re.compile(r"['\"]([a-z_]+)['\"]\s*=>\s*['\"]([^'\"]*)['\"]")
MWARR = re.compile(r"['\"]middleware['\"]\s*=>\s*\[([^\]]*)\]")

def attrs_of(seg):
    d = {}
    for k, v in KEY.findall(seg):
        d.setdefault(k, v)
    m = MWARR.search(seg)
    if m:
        d["middleware"] = ",".join(x.strip().strip("'\"") for x in m.group(1).split(","))
    return d

rows = []
for dirpath, _dirs, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith(".php"):
            continue
        path = os.path.join(dirpath, fn)
        rel = os.path.relpath(path, ROOT).replace("\\", "/")
        with open(path, encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
        stack = []
        i = 0
        n = len(lines)
        while i < n:
            raw = lines[i]
            s = raw.strip()
            i += 1
            if not s or s.startswith("//") or s.startswith("*") or s.startswith("/*") or s.startswith("#"):
                continue
            gm = GROUP.search(s)
            vm = VERSION.search(s)
            em = VERB.search(s)
            if em and not gm:
                verb = em.group(1).lower()
                if verb == "any":
                    verb = "ANY"
                elif verb == "resource":
                    continue
                url_path = em.group(2)
                # meta array: from this line, extend until closing ']' of the arg array seen
                after = s[em.end():]
                meta_src = after
                if "]" not in after and "'uses'" not in after and "function" not in after:
                    # join following lines until line containing ']);'
                    while i < n and "])" not in lines[i] and ");" not in lines[i]:
                        meta_src += "\n" + lines[i].strip()
                        i += 1
                    if i < n:
                        meta_src += "\n" + lines[i].strip()
                        i += 1
                meta = attrs_of(meta_src)
                prefix = "/".join(x.get("prefix", "").strip("/") for x in stack if x.get("prefix"))
                p = url_path.strip("/")
                url = "/" + (f"{prefix}/{p}" if prefix else p)
                ns_parts = [x.get("namespace", "") for x in stack if x.get("namespace")]
                uses = meta.get("uses") or ("closure" if "function" in meta_src else "")
                rows.append({
                    "file": rel, "line": i,
                    "method": verb.upper() if verb != "ANY" else "ANY",
                    "url": url,
                    "name": meta.get("name", ""),
                    "as": meta.get("as", ""),
                    "uses": uses,
                    "namespace": "\\".join(ns_parts[-2:]),
                    "providers": meta.get("providers") or next((x.get("providers", "") for x in reversed(stack)), ""),
                    "middleware": meta.get("middleware") or next((x.get("middleware", "") for x in reversed(stack)), ""),
                    "role": meta.get("role", ""),
                })
                continue
            if gm:
                # attrs may span lines; join until 'function' appears
                seg = s
                j = 0
                while "function" not in seg and i < n and j < 5:
                    seg += " " + lines[i].strip()
                    i += 1
                    j += 1
                a = attrs_of(seg[seg.find("->group(") + 8:])
                stack.append({k: a.get(k, "") for k in ("prefix", "namespace", "middleware", "providers")})
                if re.search(r"\}\s*\)\s*;", seg) and "{" not in seg[seg.rfind("function") + 8:]:
                    stack.pop()
                continue
            if vm:
                stack.append({"prefix": "", "namespace": "", "middleware": "", "providers": ""})
                continue
            if re.match(r"^\}+\s*\)?\s*;?\s*$", s):
                if stack:
                    stack.pop()
                continue
            # standalone ->post without parens? e.g. $router->group with prefix only handled above

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(rows, f, ensure_ascii=False, indent=1)

print("TOTAL", len(rows))
print(Counter(r["file"].split("/")[0] for r in rows).most_common())
print("with uses:", sum(1 for r in rows if r["uses"] and r["uses"] != "closure"))
print("with as:", sum(1 for r in rows if r["as"]))
print("providers:", Counter(r["providers"] for r in rows).most_common(10))
