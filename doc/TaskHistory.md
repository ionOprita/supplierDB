# Task execution history and offers coverage

System → Tasks contains an expandable **Execution history and daily coverage** panel.
It initially shows the last 30 calendar days in Europe/Bucharest. Task, date and execution
status filters are available; execution history is paginated in groups of 50. Queries can
span up to 366 days, and older date ranges remain accessible. There is no automatic history
deletion.

## Attempts and retries

Every background task start records a permanent attempt in `task_runs`. Completion marks
it `SUCCESS` or `FAILED` and retains its error. A later successful attempt does not erase
earlier failures. Server startup marks unfinished attempts `INTERRUPTED`; a subsequent
attempt starts a new record. The existing latest-task status and history update together
in a database transaction.

Offers collection uses the existing hourly schedule and hourly retry interval, measured
from the preceding attempt's start. Tasks sharing an account's dashboard lane run serially;
an occupied lane or paused task can delay the next attempt. The existing administrator
Run action requests collection now, subject to the lane being available. It collects the
current rolling counters, not missing historical data.

## Daily offers coverage

Coverage is tracked per configured offers task/vendor from its registration date in
`offer_collection_sources`. Registration requires a unique vendor account match and is
retried at task start if that mapping was unavailable at startup. The date is preserved
across restarts. Days before registration are not labelled missing.

| Status | Meaning |
|---|---|
| Collected | A complete live offers/visits snapshot was saved on this date. |
| Pending | Today has no successful snapshot and no attempt started today. |
| Retrying | Today has attempts but no successful snapshot yet. |
| Missing | A past monitored date has no successful snapshot, including days with no attempts while the server was offline. |

One successful snapshot makes the day Collected even if a later retry fails. Each row
shows its latest saved collection timestamp, attempt count and latest failure/interruption
error. Attempt counts and errors belong to the date the attempt started; a fetch crossing
midnight saves its snapshot on the date it finishes. Thus a completed snapshot can exist
on a date with zero attempts started that day.

Coverage uses the date and task filters, independently of the execution status filter.
Missing days remain missing after collection resumes: tomorrow's rolling counters cannot
recover yesterday's observation. The last successful snapshot remains available to the
Product Performance panel with its original timestamp and stale label.

## API and rollout

- `GET /app/tasks/history`: optional `taskName`, `status`, `from`, `to`, `page`, `pageSize`;
  returns `items`, `total`, `page`, `pageSize`. Status is one of `RUNNING`, `SUCCESS`,
  `FAILED`, `INTERRUPTED`. Page size is 1–200.
- `GET /app/tasks/coverage`: optional `taskName`, `from`, `to`; returns `days`.
- Both endpoints require the existing signed-in application access, return no-store
  responses, use inclusive Bucharest date filters, and serialize dates/timestamps as ISO
  strings. Error details are rendered as text in expandable UI controls.

Database migrations 47 and 48 add attempt history, monitored sources and nullable visit
fields. Existing offer snapshots are preserved and do not acquire invented visit timestamps.
History begins after rollout; previous attempts cannot be reconstructed from the old
latest-status table. The first successful live collection supplies the initial visit panel
values. Updating the application/database on the server is required to activate the feature.
