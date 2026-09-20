"""Render the Surefire XML reports as a Markdown summary for the GitHub Actions job summary.

The point is that a red build is diagnosable from the summary alone: the per-suite table says how
much broke, and the list underneath names every test that failed and why, so nobody has to download
an artifact to find out what happened.
"""

import glob
import sys
import xml.etree.ElementTree as ET

COUNT_KEYS = ("tests", "failures", "errors", "skipped")

# Enough of the assertion message to identify the problem; the full text is in the artifact.
MAX_MESSAGE_CHARS = 300


def simple_name(qualified_name: str) -> str:
    return qualified_name.rsplit(".", 1)[-1]


def failures_in(suite: ET.Element) -> list[tuple[str, str]]:
    """(test name, first line of the failure message) for every failed or errored test case."""
    failed = []
    for case in suite.iter("testcase"):
        for outcome in list(case.findall("failure")) + list(case.findall("error")):
            name = f"{simple_name(case.get('classname', ''))}.{case.get('name', '?')}"
            message = (outcome.get("message") or outcome.get("type") or "").strip().splitlines()
            summary = message[0] if message else "no message"
            if len(summary) > MAX_MESSAGE_CHARS:
                summary = summary[:MAX_MESSAGE_CHARS] + "..."
            failed.append((name, summary))
    return failed


def main() -> int:
    reports = sorted(glob.glob("target/surefire-reports/TEST-*.xml"))
    if not reports:
        print("No Surefire reports were produced - the build failed before the tests ran.")
        return 0

    totals = dict.fromkeys(COUNT_KEYS, 0)
    rows = []
    failed = []
    for path in reports:
        suite = ET.parse(path).getroot()
        counts = {key: int(suite.get(key, 0)) for key in COUNT_KEYS}
        for key, value in counts.items():
            totals[key] += value
        rows.append((simple_name(suite.get("name", path)), counts))
        failed.extend(failures_in(suite))

    print("| Suite | Tests | Failures | Errors | Skipped |")
    print("| --- | ---: | ---: | ---: | ---: |")
    for name, counts in rows:
        print(f"| {name} | {counts['tests']} | {counts['failures']} | {counts['errors']} | {counts['skipped']} |")
    print(f"| **Total** | **{totals['tests']}** | **{totals['failures']}** "
          f"| **{totals['errors']}** | **{totals['skipped']}** |")

    if failed:
        print(f"\n### What failed ({len(failed)})\n")
        for name, message in failed:
            print(f"- **{name}** — {message}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
