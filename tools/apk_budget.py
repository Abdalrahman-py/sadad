#!/usr/bin/env python3
"""Fails when a release APK is over its size budget or ships without a Baseline Profile.

Usage: python3 tools/apk_budget.py APK [--budget-file FILE]

The budget is one whole number of bytes in tools/apk-budget.txt (the compressed APK file, as
`ls -l` shows it). Raising it is a reviewed edit of that one file.
"""

import argparse
import os
import re
import sys
import zipfile

DEFAULT_BUDGET_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apk-budget.txt")
PROFILE = "assets/dexopt/baseline.prof"


def read_budget(path):
    """The byte budget in `path`: exactly one positive whole number."""
    with open(path, encoding="utf-8") as f:
        text = f.read().strip()
    if not re.fullmatch(r"[0-9]+", text) or int(text) == 0:
        raise ValueError("%s must hold one positive whole number of bytes, found %r" % (path, text))
    return int(text)


def check(apk, budget):
    """Problems with `apk` against `budget` bytes; an empty list means it passes."""
    if not os.path.isfile(apk):
        return ["APK not found: %s" % apk]
    problems = []
    size = os.path.getsize(apk)
    if size > budget:
        problems.append(
            "APK is %d bytes, budget is %d bytes (%d over): %s" % (size, budget, size - budget, apk)
        )
    try:
        with zipfile.ZipFile(apk) as z:
            if PROFILE not in z.namelist():
                problems.append("APK has no %s (the Baseline Profile): %s" % (PROFILE, apk))
    except zipfile.BadZipFile:
        problems.append("APK is not a valid zip file: %s" % apk)
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("apk")
    parser.add_argument("--budget-file", default=DEFAULT_BUDGET_FILE)
    args = parser.parse_args(argv)
    try:
        budget = read_budget(args.budget_file)
    except (OSError, ValueError) as error:
        print("apk_budget: %s" % error, file=sys.stderr)
        return 1
    problems = check(args.apk, budget)
    if problems:
        for problem in problems:
            print("apk_budget: FAIL: %s" % problem, file=sys.stderr)
        return 1
    size = os.path.getsize(args.apk)
    print("apk_budget: OK: %s is %d bytes, budget %d bytes, %s present" % (args.apk, size, budget, PROFILE))
    return 0


if __name__ == "__main__":
    sys.exit(main())
