#!/usr/bin/env python3
"""Show the last steps of an OpenCode session.

    tools/oc-tail.py              newest session in this project, last 15 steps
    tools/oc-tail.py SES_ID 40    given session, last 40 steps
"""
import json, os, subprocess, sys, time

OPENCODE = os.path.expanduser("~/.opencode/bin/opencode")
sid = sys.argv[1] if len(sys.argv) > 1 else subprocess.run(
    [OPENCODE, "session", "list"], capture_output=True, text=True, check=True
).stdout.split()[0]
count = int(sys.argv[2]) if len(sys.argv) > 2 else 15
data = json.loads(subprocess.run(
    [OPENCODE, "session", "export", sid], capture_output=True, text=True, check=True
).stdout)

lines = []
for m in data["messages"]:
    for c in m.get("content") or []:
        kind = c.get("type")
        if kind == "text" and c.get("text"):
            lines.append(f"{m['type']}: {c['text'].strip()}")
        elif kind == "tool":
            state = c.get("state") or {}
            arg = json.dumps(state.get("input", ""), ensure_ascii=False)
            lines.append(f"  [{c.get('name')} {state.get('status', '')}] {arg[:160]}")

last = data["messages"][-1]
done = (last.get("time") or {}).get("completed")
age = int(time.time() - (done or last["time"]["created"]) / 1000)
cost = sum(m.get("cost") or 0 for m in data["messages"])
print(f"{sid}  {data['info'].get('title', '')}")
print(f"{len(data['messages'])} messages, ${cost:.3f}, last activity {age}s ago, last finish={last.get('finish')}")
print("\n".join(line[:400] for line in lines[-count:]))
