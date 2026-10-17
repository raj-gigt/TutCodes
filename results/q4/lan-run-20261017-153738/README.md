# Q4 two-machine run - 17 October 2026

Configuration: balanced 4 2 10 4, commit 65a8c08, 256-chunk windows.
Server exit code: 0. File: 53,186,840 bytes / 37,064 chunks.
Path 0 received 26075 assignments (70.35%); path 1 received 10989 (29.65%).
Sender duration: 136.883317 seconds, from metadata writes through end-marker writes,
excluding warmup. This does not measure client download completion.

All 145 allocations minimized the predicted completion-time difference over
possible integer splits. No minRTT fallback occurred. 55 windows differed from
bandwidth-ratio allocation on the same snapshots, assigning a net 55
extra chunks to path 0. This is a formula comparison, not another measured download.

- server.log: full captured stdout/stderr.
- Scheduler.csv: estimates and per-window allocations.
- Bandwidth-Path0.csv / Bandwidth-Path1.csv: probe samples and EWMA.
- run.json: configuration, source hash, commit, timestamps and exit status.
- summary.json: allocation checks and measurement summaries.

The [client record](../client-verification.txt) confirms successful reconstruction
of 53,186,840 bytes. Its SHA-256 matches this run's source hash. Client elapsed
time was 139.648287 seconds, from receiver start through output flush, including warmup.
The client prints probe EOF messages after completion when the server closes the
control channels; its exit code is 0.
