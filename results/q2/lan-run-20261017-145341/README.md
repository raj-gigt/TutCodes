# Q2 two-machine run - 17 October 2026

Server launched at 14:53:41 IST and exited at 14:55:21 IST with exit code 0.
The launch-to-exit interval includes waiting for the client and is not download time.
The server used commit bd9742c and minRTT scheduling, transferring a 53,186,840-byte ZIP.

- server.log: complete captured server stdout/stderr.
- Bandwidth-Path0.csv and Bandwidth-Path1.csv: raw bandwidth samples and EWMA in B/s.
- summary.json: sample count, arithmetic mean, range and final EWMA converted to Mbps.
- run.json: command, commit, settings, source hash and run status.

Both probe channels recorded five samples. Similar average rates and a simultaneous
drop are consistent with shared conditions; they do not prove independent paths or
identify the cause. The client SHA-256 supplied in results/README.md matches the
recorded server source SHA-256, confirming byte-for-byte file reconstruction.
No client download-duration measurement was captured.
