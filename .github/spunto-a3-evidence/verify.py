"""Replay functional regressions on the disposable test runner's checkout."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

TRASH = "tests/test_trash_retention.py"
BACKUP = "tests/test_backup.py"
TRASH_WINDOW = "(now - settings.trash_days * DAY_MS,),"
MUTANTS = (
    (
        "G-empty-trash-unfiltered",
        "core/notes.py",
        '"SELECT *,rowid AS fts_id FROM note WHERE trashed_at IS NOT NULL"\n',
        '"SELECT *,rowid AS fts_id FROM note"\n',
        f"{TRASH}::test_empty_trash_purges_only_trashed_notes_and_leaves_tombstones",
        "'deleted': 2",
    ),
    (
        "H-retention-window-removed",
        "jobs/maintenance.py",
        TRASH_WINDOW,
        "(now,),",
        f"{TRASH}::test_trash_purge_keeps_a_note_until_its_window_ends",
        "assert 1 == 0",
    ),
    (
        "H2-retention-one-day-early",
        "jobs/maintenance.py",
        TRASH_WINDOW,
        "(now - (settings.trash_days - 1) * DAY_MS,),",
        f"{TRASH}::test_trash_purge_keeps_a_note_until_its_window_ends",
        "assert 1 == 0",
    ),
    (
        "J-retention-calendar-days",
        "jobs/backup.py",
        "if day not in days and len(days) < keep_daily:",
        "if day not in days and (now.date() - day).days < keep_daily:",
        f"{BACKUP}::test_retention_after_downtime_preserves_snapshot_days_and_weeks[snapshot-days]",
        "assert False ==",
    ),
    (
        "J2-retention-calendar-weeks",
        "jobs/backup.py",
        "if week not in weeks and len(weeks) < keep_weekly:",
        "if week not in weeks and (now.date() - day).days < keep_weekly * 7:",
        f"{BACKUP}::test_retention_after_downtime_preserves_snapshot_days_and_weeks[snapshot-weeks]",
        "assert False ==",
    ),
    (
        "daily-schedule-ignores-configured-zone",
        "jobs/backup.py",
        "seconds_until(settings.backup_time, settings.zone, datetime.now(UTC))",
        'seconds_until(settings.backup_time, ZoneInfo("UTC"), datetime.now(UTC))',
        f"{BACKUP}::test_daily_backup_obeys_schedule_and_recovers_after_failure",
        "assert [9000.0, 9000.0, 9000.0] == [1800, 1800, 1800]",
    ),
)


def run(outputs, label, selectors, diagnostic=None):
    report = outputs / f"{label}.xml"
    with (outputs / f"{label}.log").open("w") as log:
        result = subprocess.run(
            [sys.executable, "-m", "pytest", "-q", f"--junitxml={report}", *selectors],
            stdout=log,
            stderr=subprocess.STDOUT,
            check=False,
            timeout=75,
        )
    expected = int(diagnostic is not None)
    assert result.returncode == expected, (label, result.returncode)
    suite = ET.parse(report).getroot().find("testsuite")
    assert suite is not None
    assert [suite.attrib[key] for key in ("errors", "failures", "skipped")] == [
        "0",
        str(expected),
        "0",
    ], (label, suite.attrib)
    if diagnostic is not None:
        assert suite.attrib["tests"] == "1"
        failure = suite.find(".//failure")
        assert failure is not None and diagnostic in (failure.text or ""), (
            label,
            diagnostic,
        )
    else:
        assert int(suite.attrib["tests"]) >= 40
    print(label, suite.attrib, flush=True)


def main():
    outputs = Path(sys.argv[1])
    outputs.mkdir(parents=True, exist_ok=True)
    run(outputs, "control", [BACKUP, TRASH])
    for label, filename, old, new, target, diagnostic in MUTANTS:
        source = Path("src/spunto") / filename
        original = source.read_text()
        assert original.count(old) == 1, (label, filename)
        try:
            source.write_text(original.replace(old, new))
            run(outputs, label, [target], diagnostic)
        finally:
            source.write_text(original)


if __name__ == "__main__":
    main()
