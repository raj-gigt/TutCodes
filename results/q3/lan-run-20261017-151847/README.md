# Q3 run - 17 October 2026, 15:18 IST

Configuration: bwratio 4 2 10 4, commit aeaaf12, 256-chunk windows.
The server exited with code 1 after a probe connection reset and a data-path
Broken pipe. The cause on the client/network side is not established.

Scheduler.csv contains 126 windows and 32,256 assignments out of 37,064 file
chunks. Every logged allocation matches the bandwidth-ratio formula, with no
fallbacks. These are assignments, not verified deliveries; the final window may
be incomplete. There is no successful completion time or verified output hash.

Raw server output, scheduler decisions, and bandwidth samples are retained here.
summary.json contains statistics for this incomplete run. run.json records settings,
source hash, commit, and exit status. Do not use this run as a completed-download
benchmark.
