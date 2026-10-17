# Q3 retry - 17 October 2026, 15:24 IST

Configuration: bwratio 4 2 10 4, 256-chunk windows, commit c76d189.
The same configuration was rerun after the earlier connection reset.
The server completed successfully (exit code 0); the earlier failure did not recur.
Its cause remains unknown without client-side evidence from the failed attempt.

```text
File bytes:       53186840
Total chunks:     37064
Path 0 chunks:    25978
Path 1 chunks:    11086
Sender duration: 135.395867 seconds
```

All 145 windows matched the bandwidth-ratio allocation; there were no fallbacks.
Sender duration covers metadata writes through end-marker writes, excluding warmup.
It is not a measurement of client-side download completion. Client completion/hash
confirmation for this retry is pending; do not reuse a hash from an earlier run.

- server.log: full server console output.
- Scheduler.csv: window allocations and estimates used.
- Bandwidth-Path0.csv / Bandwidth-Path1.csv: raw probe samples and smoothed estimates.
- run.json: settings, source hash, commit and server exit status.
- summary.json: allocation validation, path counts and bandwidth statistics.
