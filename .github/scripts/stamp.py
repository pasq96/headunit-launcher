import sys, time

# Prefixes every line with the seconds since the build started: the gaps between "> Task" lines show where the time goes.
t0 = time.monotonic()
for line in sys.stdin:
    sys.stdout.write(f"[{time.monotonic() - t0:7.1f}s] {line}")
    sys.stdout.flush()
