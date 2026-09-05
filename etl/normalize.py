"""DEPRECATED — kept only so older commands keep working. Use `clean.py`.

The cleaning logic moved out of Python and into `rules.yml`, where every rule carries a
`why:` citing the organisers' data dictionary. `clean.py` interprets that file. Having the
rules in one auditable place, rather than spread through pandas calls, is the whole point;
keeping a second implementation here would guarantee they drift apart.

This shim forwards to clean.py so nothing that called `normalize.py --src X --out Y`
breaks.
"""
from __future__ import annotations

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

if __name__ == "__main__":
    print("note: normalize.py is deprecated — forwarding to clean.py (STAGE 2)\n",
          file=sys.stderr)
    args = ["--only" if a == "--only" else a for a in sys.argv[1:]]
    sys.exit(subprocess.call([sys.executable, os.path.join(HERE, "clean.py"), *args]))
