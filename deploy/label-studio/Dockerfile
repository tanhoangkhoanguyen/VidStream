# syntax=docker/dockerfile:1
# Label Studio 1.23.2 + one fix: the Data Manager queue (used when the annotator
# filters, sorts or selects tasks) skips tasks locked by someone else.
# Upstream takes the first unsolved task without a lock check, so two annotators
# can get the same task. See docs/spikes/label-studio/distribution.md.
FROM heartexlabs/label-studio:1.23.2

RUN python3 - <<'EOF'
from pathlib import Path

f = Path("/label-studio/label_studio/projects/functions/next_task.py")
old = """tries sequence sampling from prepared tasks')
                next_task = not_solved_tasks.first()"""
new = """tries sequence sampling from prepared tasks')
                next_task = _get_first_unlocked(not_solved_tasks, user)"""
src = f.read_text()
# Fails the build after an upgrade that changed this code: re-check the fix.
assert src.count(old) == 1, "next_task.py changed upstream"
f.write_text(src.replace(old, new))
EOF
