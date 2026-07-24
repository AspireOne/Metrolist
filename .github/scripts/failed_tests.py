#!/usr/bin/env python3
"""Print an identifier ("classname#name#failuretype") for every failed or errored
testcase found in a set of JUnit XML report files.

Usage:
    failed_tests.py '<glob>'

The single argument is a shell-style glob (quoted, so this script expands it, not
the shell) pointing at JUnit result XML, e.g.
    'app/build/test-results/testFossDebugUnitTest/*.xml'

Exit codes:
    0 - reports parsed; identifiers (possibly none) written to stdout, one per line
    2 - no report files matched the glob   (caller should fail closed)
    3 - a report file could not be parsed  (caller should fail closed)

The subset check lives in the workflow: a merged-build failure is tolerated only
when the identical test fails on pristine upstream with the same failure type.
Failing closed on missing or unparseable reports prevents a compile/config error
from masquerading as "no test failures" and slipping a broken build through.

Known limitation: the failure *message* (and stack trace) is intentionally NOT
part of the identifier. Messages carry line numbers, timestamps and object hashes
that differ run-to-run, so comparing them would spuriously block releases. The
failure *type* is included, so a same-named test that fails upstream and on our
fork with different exception types is correctly treated as a new (blocking)
failure; a same-name/same-type failure with a different underlying cause is not
distinguished, and is treated as inherited.
"""
import glob
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: failed_tests.py '<glob>'", file=sys.stderr)
        return 3

    files = glob.glob(sys.argv[1])
    if not files:
        print(f"no JUnit report files matched: {sys.argv[1]}", file=sys.stderr)
        return 2

    ids = set()
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print(f"could not parse {path}: {exc}", file=sys.stderr)
            return 3
        for tc in root.iter("testcase"):
            node = tc.find("failure")
            if node is None:
                node = tc.find("error")
            if node is not None:
                classname = tc.get("classname", "")
                name = tc.get("name", "")
                ftype = node.get("type", "")
                ids.add(f"{classname}#{name}#{ftype}")

    for ident in sorted(ids):
        print(ident)
    return 0


if __name__ == "__main__":
    sys.exit(main())
