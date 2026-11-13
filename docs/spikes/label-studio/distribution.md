# Label Studio task distribution

Goal: the label stream never gives one task to two annotators. Label Studio 1.23.2 on Postgres, `maximum_annotations=1`, 3 annotators. Checked 2026-11-11.

## How it works

- "Label All Tasks" in the UI calls `POST /api/dm/actions?id=next_task&tabID=…&project=…` (seen in the browser's network log). It runs `get_next_task()` in a transaction.
- **Default queue** (no filter, sort or selection): picks a task with `select_for_update(skip_locked=True)` and skips tasks locked by others, then `set_lock(user)`.
- **Data Manager queue** (the annotator filtered, sorted or selected tasks): upstream takes `not_solved_tasks.first()` **without a lock check**, then sets a lock anyway.

## Results

A and B request the next task at the same moment, then submit. Repeated per round.

| Test | Stock 1.23.2 | Patched ([deploy/label-studio/Dockerfile](../../../deploy/label-studio/Dockerfile)) |
|---|---|---|
| Default queue, 10 rounds | 0 duplicates | 0 duplicates |
| Filter + sort descending, 5 rounds | **5/5 duplicates** (30, 30, 29, 29, …) | 0 duplicates, order kept (29, 30, 28, 27, …) |
| Nothing left | 404 | 404 |

The fix replaces one line with `_get_first_unlocked(not_solved_tasks, user)`, the helper the default queue already uses. The build fails if upstream changes that code.

Same in both images:

| Test | Result |
|---|---|
| A holds the only open task; C asks | 404, C does not get it |
| A's lock expires (set in the DB); C asks | C gets the task |
| Lock lifetime | `TASK_LOCK_TTL`, default **86400 s (24 h)**. Set to `3600` → 3600 s. |
| B submits to A's locked task directly (API), twice | 201, 201. Locks and `maximum_annotations` are not enforced on submit. |

## Decisions for the build

- Use the patched image. Annotators may filter and sort.
- `TASK_LOCK_TTL=3600`: an abandoned task returns to the queue after 1 h, not 24 h.
- The server accepts several annotations per task (latest wins; every annotator is recorded). The report counts duplicated units.

## Open

- Opening a task by clicking its row in the Data Manager: not tested. If it sets no lock, two people can open the same task. Covered by the rule above; the labeler guide says to use "Label All Tasks".
- Upstream fix: report to HumanSignal (needs the owner's approval to post).
