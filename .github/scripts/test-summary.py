#!/usr/bin/env python3
"""Write Maven test results to the GitHub Actions job summary."""

import glob
import os
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    lines = [
        "## Test Results",
        "",
        "| Suite | Tests | Passed | Failed | Errors | Skipped |",
        "|-------|------:|-------:|-------:|-------:|--------:|",
    ]
    reports = sorted(
        glob.glob("**/target/surefire-reports/TEST-*.xml", recursive=True)
        + glob.glob("**/target/failsafe-reports/TEST-*.xml", recursive=True)
    )
    for report in reports:
        suite = ET.parse(report).getroot()
        tests, failed, errors, skipped = (
            int(suite.get(key, "0"))
            for key in ("tests", "failures", "errors", "skipped")
        )
        passed = tests - failed - errors - skipped
        name = suite.get("name", Path(report).stem).replace("|", "\\|")
        lines.append(f"| {name} | {tests} | {passed} | {failed} | {errors} | {skipped} |")
    if not reports:
        lines.extend(["", "No test reports are available; test execution is unverified."])
    summary = "\n".join(lines) + "\n"
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(summary)


if __name__ == "__main__":
    main()
