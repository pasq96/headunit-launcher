import re, sys

# Slowest Gradle tasks from a stamped build.log: a task "lasts" until the next one starts, which is accurate for the
# long sequential ones (KSP, Kotlin, R8, packaging) and only approximate for the short parallel ones.
rows, end = [], 0.0
for line in open(sys.argv[1], errors="replace"):
    m = re.match(r"\[\s*([\d.]+)s\] (.*)", line)
    if not m:
        continue
    t, text = float(m.group(1)), m.group(2)
    end = t
    task = re.match(r"> Task (\S+)(?: (\S+))?", text)
    if task:
        rows.append((t, task.group(1), task.group(2) or ""))
rows = [(t, name, out, (rows[i + 1][0] if i + 1 < len(rows) else end) - t) for i, (t, name, out) in enumerate(rows)]
print(f"Gradle: {end:.0f} s in total, {len(rows)} tasks started\n")
print("| s | task | outcome |\n|---:|---|---|")
for t, name, out, d in sorted(rows, key=lambda r: -r[3])[:14]:
    print(f"| {d:5.1f} | `{name}` | {out or 'executed'} |")
