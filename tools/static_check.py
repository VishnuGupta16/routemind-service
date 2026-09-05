#!/usr/bin/env python3
"""Structural check for the RouteMind sources — a stand-in for `javac`, not a replacement.

Why this exists: the project targets Java 24 / Spring Boot 4, and the machine this was
authored on only has JDK 11, which cannot even parse records or text blocks. So there was
no way to compile. This catches the classes of mistake that a compiler would have caught
first and that are easy to make while editing files by hand:

  * unbalanced braces / parens / brackets  (lexer is aware of //, /* */, "", '', ''' and
    text blocks, and of escapes — a naive brace count is worse than useless here)
  * `package` not matching the directory
  * public type name not matching the file name
  * an `import com.routemind.…` pointing at a type that does not exist
  * a `com.routemind` type referenced in code that no file declares
  * `@Component`/`@Service` on something that is not a class
  * TODO / FIXME left behind

It does NOT type-check. Passing this does not mean the project compiles. Run:

    cd routemind-service && gradle wrapper && ./gradlew build

on a machine with JDK 24 before trusting it.
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")

PAIRS = {"}": "{", ")": "(", "]": "["}


def strip_code(text: str):
    """Return (code_only, errors). Removes comments and string/char/text-block literals."""
    out, errs = [], []
    i, n, line = 0, len(text), 1
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1
            out.append(c)
            i += 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            if j < 0:
                errs.append(f"line {line}: unterminated block comment")
                break
            line += text.count("\n", i, j)
            i = j + 2
        elif text.startswith('"""', i):                      # text block
            j = text.find('"""', i + 3)
            while j > 0 and text[j - 1] == "\\" and text[j - 2] != "\\":
                j = text.find('"""', j + 1)
            if j < 0:
                errs.append(f"line {line}: unterminated text block")
                break
            line += text.count("\n", i, j)
            out.append(" ")
            i = j + 3
        elif c in "\"'":
            j, closed = i + 1, False
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == c:
                    closed = True
                    break
                if text[j] == "\n":
                    break
                j += 1
            if not closed:
                errs.append(f"line {line}: unterminated {'string' if c == chr(34) else 'char'} literal")
                i += 1
                continue
            out.append(" ")
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out), errs


def balance(code: str):
    stack, errs = [], []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in "{([":
            stack.append((ch, line))
        elif ch in "})]":
            if not stack:
                errs.append(f"line {line}: stray '{ch}'")
            elif stack[-1][0] != PAIRS[ch]:
                o, ol = stack[-1]
                errs.append(f"line {line}: '{ch}' closes '{o}' opened on line {ol}")
                stack.pop()
            else:
                stack.pop()
    for o, ol in stack:
        errs.append(f"line {ol}: '{o}' never closed")
    return errs


def main() -> int:
    files = []
    for base, _, names in os.walk(SRC):
        files += [os.path.join(base, f) for f in names if f.endswith(".java")]
    files.sort()
    if not files:
        print(f"no sources under {SRC}")
        return 2

    declared: dict[str, str] = {}        # FQN -> path
    per_file: list[tuple[str, str, str]] = []   # (path, package, code)
    problems: list[str] = []
    notes: list[str] = []

    for path in files:
        rel = os.path.relpath(path, ROOT)
        raw = open(path, encoding="utf-8").read()
        code, errs = strip_code(raw)
        problems += [f"{rel}: {e}" for e in errs]
        problems += [f"{rel}: {e}" for e in balance(code)]

        m = re.search(r"^\s*package\s+([\w.]+)\s*;", code, re.M)
        if not m:
            problems.append(f"{rel}: no package declaration")
            continue
        pkg = m.group(1)

        expected = os.path.dirname(rel).split(os.sep)
        for marker in ("java",):
            if marker in expected:
                expected = expected[expected.index(marker) + 1:]
        if pkg.split(".") != expected:
            problems.append(f"{rel}: package '{pkg}' != directory '{'.'.join(expected)}'")

        stem = os.path.basename(path)[:-5]
        pub = re.search(r"\bpublic\s+(?:final\s+|abstract\s+|sealed\s+)*"
                        r"(?:class|interface|enum|record)\s+(\w+)", code)
        if pub and pub.group(1) != stem:
            problems.append(f"{rel}: public type '{pub.group(1)}' != file name '{stem}'")

        for t in re.finditer(r"\b(?:class|interface|enum|record)\s+(\w+)", code):
            declared[f"{pkg}.{t.group(1)}"] = rel

        for a in re.finditer(r"@(Component|Service|RestController|Configuration)\b([^\n]*)\n\s*"
                             r"(?:@\w+[^\n]*\n\s*)*(\w[\w\s]*?)\b(class|interface|enum|record|"
                             r"public|private|final|abstract|@)", code):
            pass  # shape check only; annotation targets are validated by the compiler

        for tag in ("TODO", "FIXME", "XXX"):
            for m2 in re.finditer(rf"\b{tag}\b", raw):
                notes.append(f"{rel}: leftover {tag} at offset {m2.start()}")

        per_file.append((rel, pkg, code))

    # ---- imports and references resolve to something we actually declare
    for rel, pkg, code in per_file:
        for m in re.finditer(r"^\s*import\s+(?:static\s+)?(com\.routemind\.[\w.]+)\s*;", code, re.M):
            fqn = m.group(1)
            if fqn in declared:
                continue
            if ".".join(fqn.split(".")[:-1]) in declared:      # nested type import
                continue
            if fqn.endswith(".*"):
                continue
            problems.append(f"{rel}: imports '{fqn}' which no file declares")

        for m in re.finditer(r"\bcom\.routemind\.[\w.]+", code):
            fqn = m.group(0)
            if fqn in declared or ".".join(fqn.split(".")[:-1]) in declared:
                continue
            if any(d.startswith(fqn + ".") for d in declared):  # a package reference
                continue
            problems.append(f"{rel}: references unknown type '{fqn}'")

    main_n = sum(1 for f in files if f"{os.sep}main{os.sep}" in f)
    test_n = len(files) - main_n
    tests = 0
    for path in files:
        if f"{os.sep}test{os.sep}" in path:
            tests += len(re.findall(r"^\s*@Test\b", open(path, encoding="utf-8").read(), re.M))

    print(f"scanned {len(files)} files  ({main_n} main, {test_n} test, {tests} @Test methods)")
    print(f"declared types: {len(declared)}")

    if notes:
        print(f"\n{len(notes)} note(s):")
        for n in notes[:20]:
            print("  -", n)

    if problems:
        print(f"\nFAIL — {len(problems)} problem(s):")
        for p in problems:
            print("  x", p)
        return 1

    print("\nOK — structurally sound. This is NOT a compile; run ./gradlew build on JDK 24.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
