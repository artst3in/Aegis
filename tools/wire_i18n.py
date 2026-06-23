#!/usr/bin/env python3
"""Wire hardcoded UI literals to stringResource(R.string.key) — hardened.

Only replaces a CLEANLY-DELIMITED, complete double-quoted literal that
exactly equals an extracted value. Skips escaped-quote literals, string
templates, when-branch keys, and obvious logic lines. The Kotlin compiler
is the second gate (stringResource outside @Composable won't compile),
and per-file runs let a bad file be reverted in isolation.
"""
import re, sys, html

STRINGS = "app/src/main/res/values/strings.xml"
DELIM_AFTER = set(", )+}];:\n\t")      # valid char right after the closing quote
BAD_BEFORE  = set("\\$\"")               # opening quote must not follow these

def load_strings():
    txt = open(STRINGS, encoding="utf-8").read()
    out = {}
    for m in re.finditer(r'<string name="([a-z0-9_]+)">(.*?)</string>', txt, re.S):
        key, raw = m.group(1), m.group(2)
        # Reject anything that won't be a clean single-line plain literal.
        if any(t in raw for t in ('\\"', '&quot;', '&#', '\\n', '\n', '%', '$')):
            continue
        v = raw.replace("\\'", "'")
        v = html.unescape(v)              # &amp; &lt; &gt;
        v = v.replace("\\@", "@").replace("\\?", "?")
        if '"' in v or '$' in v or '\n' in v:
            continue
        out[v] = key
    return dict(sorted(out.items(), key=lambda kv: -len(kv[0])))

SKIP_LINE = re.compile(
    r'(Log\.|DiagLog|ConnectionLog|\.equals\(|\.startsWith\(|\.contains\(|=="|!="|'
    r'R\.string|"""|^\s*//|^\s*\*|^package |^import |android:|"file:|"http|'
    r'const val|annotation|@SerialName|putExtra\(|getString\(|setPackage\()'
)

def replace_in_line(line, strings):
    if SKIP_LINE.search(line):
        return line, 0
    n = 0
    for val, key in strings.items():
        lit = '"' + val + '"'
        start = 0
        while True:
            idx = line.find(lit, start)
            if idx < 0:
                break
            before = line[idx - 1] if idx > 0 else ' '
            after_i = idx + len(lit)
            after = line[after_i] if after_i < len(line) else '\n'
            # Clean boundaries only.
            if before in BAD_BEFORE or before.isalnum():
                start = idx + 1; continue
            if after not in DELIM_AFTER:
                start = idx + 1; continue
            # when-branch KEY position: a literal followed (later on the
            # line) by '->' is a condition, not UI text — skip.
            if '->' in line[after_i:]:
                start = idx + 1; continue
            repl = f"stringResource(R.string.{key})"
            line = line[:idx] + repl + line[after_i:]
            start = idx + len(repl)
            n += 1
    return line, n

def wire(path, strings):
    src = open(path, encoding="utf-8").read()
    lines = src.split("\n")
    total = 0
    for i, line in enumerate(lines):
        lines[i], c = replace_in_line(line, strings)
        total += c
    if total == 0:
        return 0
    out = "\n".join(lines)
    inserts = []
    if "import androidx.compose.ui.res.stringResource" not in out:
        inserts.append("import androidx.compose.ui.res.stringResource")
    if "import app.aether.aegis.R\n" not in out and "\nimport app.aether.aegis.R " not in out:
        inserts.append("import app.aether.aegis.R")
    if inserts:
        block = "".join(x + "\n" for x in inserts)
        out = re.sub(r'(\nimport [^\n]+\n)(?!import )', r'\1' + block, out, count=1)
    open(path, "w", encoding="utf-8").write(out)
    return total

if __name__ == "__main__":
    strings = load_strings()
    print(f"(simple-literal keys: {len(strings)})")
    total = 0
    for p in sys.argv[1:]:
        c = wire(p, strings)
        if c: print(f"  {c:4d}  {p.split('/')[-1]}")
        total += c
    print(f"total: {total}")
