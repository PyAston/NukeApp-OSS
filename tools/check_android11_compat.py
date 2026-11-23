#!/usr/bin/env python3
"""Guard against Java APIs unavailable on Android 11 (API 30)."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
legacy = re.compile(r"(?<![A-Za-z0-9_])(?:java\.lang\.)?ClassValue\s*<")
bad = []
for base in [root / "apps/module/src/main", root / "core"]:
    for f in base.rglob("*.kt"):
        if "/src/test/" in f.as_posix() or "/src/androidTest/" in f.as_posix():
            continue
        for line_number, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if line.strip().startswith(("//", "/*", "*")):
                continue
            if legacy.search(line):
                bad.append(f"{f.relative_to(root)}:{line_number}: {line.strip()}")
if bad:
    print("FAIL: java.lang.ClassValue requires API 34 and will fail on Android 11")
    print("\n".join(bad))
    sys.exit(1)
print("PASS: no API 34 java.lang.ClassValue usage found in production Kotlin")
